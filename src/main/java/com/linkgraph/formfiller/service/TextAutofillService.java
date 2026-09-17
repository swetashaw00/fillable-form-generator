package com.linkgraph.formfiller.service;

import com.linkgraph.formfiller.model.FieldSpec;
import com.linkgraph.formfiller.model.FieldType;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Matches pasted freeform text to a document's fields without calling any
 * external API. Two passes:
 *
 * 1. "Label / Value" pairs are parsed directly, one per line -- either
 *    "Label: Value" (also "-" / "=") or a table-style paste (columns
 *    separated by a tab, or by 2+ spaces if the paste target flattened
 *    tabs) copied straight out of Excel/Sheets/Word. This is the strongest
 *    signal.
 * 2. Field names and line labels are both reduced to a "canonical concept"
 *    (name, email, phone, address, ...) via substring matching, so a field
 *    named "your_email" still matches a line labeled "Email Address:", and
 *    a field labeled "Trading Name" still matches a line labeled "Company
 *    Name:".
 *
 * Standalone entities (email/phone/date/zip) not attached to any label are
 * picked up via regex as a last-resort fallback for fields of that concept.
 */
@Service
public class TextAutofillService {

    private static final Pattern LABEL_VALUE_LINE =
            Pattern.compile("^\\s*([A-Za-z][A-Za-z0-9 _/'-]{0,39}?)\\s*[:\\-=]\\s*(.+?)\\s*$");
    private static final Pattern TABLE_TAB_SEPARATOR = Pattern.compile("\\t+");
    private static final Pattern TABLE_SPACE_SEPARATOR = Pattern.compile(" {2,}");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    private static final Pattern PHONE =
            Pattern.compile("(\\+?\\d{1,3}[\\s.-]?)?\\(?\\d{3}\\)?[\\s.-]?\\d{3}[\\s.-]?\\d{4}");
    private static final Pattern DATE = Pattern.compile(
            "\\b\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}\\b|\\b(?:January|February|March|April|May|June|July|" +
                    "August|September|October|November|December)\\s+\\d{1,2},?\\s+\\d{4}\\b");
    private static final Pattern ZIP = Pattern.compile("\\b\\d{5}(?:-\\d{4})?\\b");

    // Checked top-to-bottom, so a more specific concept (email) wins over a
    // more generic one whose substring it happens to contain (e.g. within
    // "emailaddress"). Each row is {canonicalKey, alias, alias, ...}.
    private static final String[][] CANONICAL_CONCEPTS = {
            {"email"},
            {"dob", "dateofbirth", "birthdate", "birthday"},
            {"phone", "mobile", "cell", "telephone", "contactnumber"},
            {"firstname", "givenname"},
            {"lastname", "surname", "familyname"},
            {"zip", "zipcode", "postalcode", "postcode"},
            {"ssn", "socialsecurity"},
            {"address", "street"},
            {"city", "town"},
            {"state", "province"},
            {"country", "nation"},
            {"company", "employer", "organization", "organisation", "businessname", "tradingname", "dba"},
            {"jobtitle", "position", "designation"},
            {"name"},
            {"date"},
    };

    public List<FieldSpec> autofill(List<FieldSpec> fields, String text) {
        Map<String, String> labeledValues = extractLabeledPairs(text == null ? "" : text);
        Map<String, String> canonicalValues = buildCanonicalValues(labeledValues, text == null ? "" : text);

        for (FieldSpec field : fields) {
            if (field.getType() == FieldType.SIGNATURE) {
                continue;
            }
            String value = labeledValues.get(squish(field.getName()));
            if (value == null) {
                String canonical = canonicalOf(field.getName());
                value = canonical == null ? null : canonicalValues.get(canonical);
            }
            if (value != null && !value.isBlank()) {
                field.setValue(value);
            }
        }
        return fields;
    }

    private Map<String, String> buildCanonicalValues(Map<String, String> labeledValues, String text) {
        Map<String, String> canonicalValues = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : labeledValues.entrySet()) {
            String canonical = canonicalOf(entry.getKey());
            if (canonical != null) {
                canonicalValues.putIfAbsent(canonical, entry.getValue());
            }
        }

        addRegexFallback(canonicalValues, "email", EMAIL, text);
        addRegexFallback(canonicalValues, "phone", PHONE, text);
        addRegexFallback(canonicalValues, "zip", ZIP, text);
        addRegexFallback(canonicalValues, "date", DATE, text);

        String dateFallback = canonicalValues.get("date");
        if (dateFallback != null) {
            canonicalValues.putIfAbsent("dob", dateFallback);
        }

        if (!canonicalValues.containsKey("name")) {
            String first = canonicalValues.get("firstname");
            String last = canonicalValues.get("lastname");
            String combined = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
            if (!combined.isBlank()) {
                canonicalValues.put("name", combined);
            } else if (canonicalValues.containsKey("company")) {
                // No personal name was given -- on a business form a bare "Name"
                // field almost always means the company/trading name, so use
                // that instead of leaving it blank.
                canonicalValues.put("name", canonicalValues.get("company"));
            }
        }

        return canonicalValues;
    }

    private Map<String, String> extractLabeledPairs(String text) {
        Map<String, String> pairs = new LinkedHashMap<>();
        for (String rawLine : text.split("\\r?\\n")) {
            String[] labelAndValue = splitLabelAndValue(rawLine);
            if (labelAndValue == null) {
                continue;
            }
            String label = squish(labelAndValue[0]);
            String value = labelAndValue[1].trim();
            if (!label.isEmpty() && !value.isEmpty()) {
                pairs.putIfAbsent(label, value);
            }
        }
        return pairs;
    }

    private String[] splitLabelAndValue(String line) {
        Matcher colon = LABEL_VALUE_LINE.matcher(line);
        if (colon.matches()) {
            return new String[]{colon.group(1), colon.group(2)};
        }
        // Table-style paste (straight out of Excel/Sheets/Word): columns are
        // separated by a literal tab, or by 2+ spaces if the paste target
        // flattened tabs to spaces.
        Matcher tab = TABLE_TAB_SEPARATOR.matcher(line);
        if (tab.find()) {
            return splitAt(line, tab.start(), tab.end());
        }
        Matcher spaces = TABLE_SPACE_SEPARATOR.matcher(line);
        if (spaces.find()) {
            return splitAt(line, spaces.start(), spaces.end());
        }
        return null;
    }

    private String[] splitAt(String line, int separatorStart, int separatorEnd) {
        String label = line.substring(0, separatorStart).trim();
        String value = line.substring(separatorEnd).trim();
        if (label.isEmpty() || value.isEmpty() || label.length() > 40) {
            return null;
        }
        return new String[]{label, value};
    }

    private void addRegexFallback(Map<String, String> canonicalValues, String canonical, Pattern pattern,
                                   String text) {
        if (canonicalValues.containsKey(canonical)) {
            return;
        }
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            canonicalValues.put(canonical, m.group().trim());
        }
    }

    private String canonicalOf(String rawName) {
        String squished = squish(rawName);
        if (squished.isEmpty()) {
            return null;
        }
        for (String[] concept : CANONICAL_CONCEPTS) {
            String canonical = concept[0];
            if (squished.contains(canonical)) {
                return canonical;
            }
            for (int i = 1; i < concept.length; i++) {
                if (squished.contains(concept[i])) {
                    return canonical;
                }
            }
        }
        return null;
    }

    private String squish(String raw) {
        return raw == null ? "" : raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
