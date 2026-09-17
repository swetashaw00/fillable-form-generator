package com.linkgraph.formfiller.service;

import com.linkgraph.formfiller.model.FieldSpec;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceCharacteristicsDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceEntry;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bakes a list of FieldSpec placements into a real AcroForm on top of the
 * working PDF, producing the final fillable PDF.
 */
@Service
public class FormBuilderService {

    private static final String DEFAULT_FONT_RESOURCE_NAME = "Helv";

    public void buildFillablePdf(Path sourcePdf, List<FieldSpec> fields, Path outputPdf) throws IOException {
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(sourcePdf.toFile())) {
            PDAcroForm acroForm = new PDAcroForm(document);
            document.getDocumentCatalog().setAcroForm(acroForm);

            PDFont helvetica = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName(DEFAULT_FONT_RESOURCE_NAME), helvetica);
            acroForm.setDefaultResources(resources);
            acroForm.setDefaultAppearance("/" + DEFAULT_FONT_RESOURCE_NAME + " 10 Tf 0 g");

            Map<String, Integer> nameCounts = new HashMap<>();

            for (FieldSpec spec : fields) {
                if (spec.getPage() < 0 || spec.getPage() >= document.getNumberOfPages()) {
                    continue;
                }
                PDPage page = document.getPage(spec.getPage());
                PDRectangle rect = new PDRectangle(
                        (float) spec.getX(), (float) spec.getY(),
                        (float) spec.getWidth(), (float) spec.getHeight());
                String uniqueName = uniqueFieldName(sanitizeFieldName(spec.getName()), nameCounts);
                boolean hasValue = spec.getValue() != null && !spec.getValue().isBlank();

                PDField field = switch (spec.getType()) {
                    case CHECKBOX -> buildCheckBox(document, acroForm, uniqueName, rect, hasValue && isChecked(spec.getValue()));
                    // DATE and SIGNATURE render as ordinary fillable text fields --
                    // PDF has no native date-picker widget, and a real signature
                    // field requires a certificate-backed digital signature workflow,
                    // which is out of scope here.
                    case TEXT, DATE, SIGNATURE -> buildTextField(document, acroForm, uniqueName, rect, spec.isRequired(), hasValue);
                };

                acroForm.getFields().add(field);
                PDAnnotationWidget widget = field.getWidgets().get(0);
                widget.setPage(page);
                page.getAnnotations().add(widget);

                // Also generates a real appearance stream now rather than relying
                // on NeedAppearances -- PDFBox silently drops that flag on save in
                // this version, and not every viewer regenerates appearances for
                // fields that were never focused.
                if (field instanceof PDTextField textField) {
                    textField.setValue(hasValue ? spec.getValue() : hintText(spec));
                }
            }

            document.save(outputPdf.toFile());
        }
    }

    private PDTextField buildTextField(PDDocument document, PDAcroForm acroForm, String name,
                                        PDRectangle rect, boolean required, boolean hasValue) throws IOException {
        PDTextField textField = new PDTextField(acroForm);
        textField.setPartialName(name);
        // Light grey for the pre-filled hint text in hintText() so it reads as
        // "placeholder-ish" rather than a real answer; black when it's a genuine
        // autofilled value.
        textField.setDefaultAppearance("/" + DEFAULT_FONT_RESOURCE_NAME + " 10 Tf " + (hasValue ? "0 g" : "0.6 g"));
        if (required) {
            textField.setRequired(true);
        }

        PDAnnotationWidget widget = new PDAnnotationWidget();
        widget.setRectangle(rect);
        widget.setBorderStyle(borderStyle());

        PDAppearanceCharacteristicsDictionary mk =
                new PDAppearanceCharacteristicsDictionary(new COSDictionary());
        mk.setBorderColour(new PDColor(new float[]{0f, 0f, 0f}, PDDeviceGray.INSTANCE));
        widget.setAppearanceCharacteristics(mk);

        textField.setWidgets(List.of(widget));
        return textField;
    }

    private PDCheckBox buildCheckBox(PDDocument document, PDAcroForm acroForm, String name,
                                      PDRectangle rect, boolean checked) throws IOException {
        PDCheckBox checkBox = new PDCheckBox(acroForm);
        checkBox.setPartialName(name);

        PDAnnotationWidget widget = new PDAnnotationWidget();
        widget.setRectangle(rect);
        widget.setBorderStyle(borderStyle());

        PDAppearanceCharacteristicsDictionary mk =
                new PDAppearanceCharacteristicsDictionary(new COSDictionary());
        mk.setBorderColour(new PDColor(new float[]{0f, 0f, 0f}, PDDeviceGray.INSTANCE));
        widget.setAppearanceCharacteristics(mk);

        PDRectangle localBox = new PDRectangle(rect.getWidth(), rect.getHeight());
        PDAppearanceStream offAppearance = checkboxAppearance(document, localBox, false);
        PDAppearanceStream onAppearance = checkboxAppearance(document, localBox, true);

        COSDictionary normalStates = new COSDictionary();
        normalStates.setItem(COSName.getPDFName("Off"), offAppearance.getCOSObject());
        normalStates.setItem(COSName.getPDFName("Yes"), onAppearance.getCOSObject());

        PDAppearanceDictionary appearanceDictionary = new PDAppearanceDictionary();
        appearanceDictionary.getCOSObject().setItem(COSName.N, normalStates);
        widget.setAppearance(appearanceDictionary);
        widget.setAppearanceState(checked ? "Yes" : "Off");

        checkBox.setWidgets(List.of(widget));
        checkBox.getCOSObject().setName(COSName.V, checked ? "Yes" : "Off");
        return checkBox;
    }

    private boolean isChecked(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("yes") || normalized.equals("true") || normalized.equals("x")
                || normalized.equals("checked") || normalized.equals("on") || normalized.equals("1");
    }

    private PDAppearanceStream checkboxAppearance(PDDocument document, PDRectangle box, boolean checked)
            throws IOException {
        PDAppearanceStream stream = new PDAppearanceStream(document);
        stream.setBBox(box);
        stream.setResources(new PDResources());

        try (var contents = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, stream)) {
            contents.setLineWidth(1f);
            contents.addRect(0.5f, 0.5f, box.getWidth() - 1f, box.getHeight() - 1f);
            contents.stroke();
            if (checked) {
                float margin = Math.min(box.getWidth(), box.getHeight()) * 0.2f;
                contents.moveTo(margin, margin);
                contents.lineTo(box.getWidth() - margin, box.getHeight() - margin);
                contents.moveTo(margin, box.getHeight() - margin);
                contents.lineTo(box.getWidth() - margin, margin);
                contents.stroke();
            }
        }
        return stream;
    }

    private PDBorderStyleDictionary borderStyle() {
        PDBorderStyleDictionary style = new PDBorderStyleDictionary();
        style.setWidth(1);
        return style;
    }

    // The field "name" set in the editor is only the AcroForm field's internal
    // identifier (like an HTML <input name="...">) -- it's never rendered on the
    // page by itself. For fields with no adjacent printed label (typically ones
    // manually drawn onto blank space), that leaves no visible clue what the box
    // is for. Pre-filling with a prettified version of the name is the closest
    // a plain AcroForm field gets to an HTML placeholder -- real content the
    // filler must select and overwrite, not a value that clears itself on focus.
    private String hintText(FieldSpec spec) {
        return switch (spec.getType()) {
            case SIGNATURE -> "Sign here";
            case DATE -> prettifyName(spec.getName()) + " (e.g. MM/DD/YYYY)";
            default -> prettifyName(spec.getName());
        };
    }

    private String prettifyName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "";
        }
        String[] words = rawName.replace('_', ' ').replace('-', ' ').trim().split("\\s+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private String uniqueFieldName(String requestedName, Map<String, Integer> nameCounts) {
        String base = (requestedName == null || requestedName.isBlank()) ? "field" : requestedName;
        int count = nameCounts.merge(base, 1, Integer::sum);
        return count == 1 ? base : base + "_" + count;
    }

    // PDFBox/the PDF spec forbids a period in a field's partial name (it's the
    // separator for fully-qualified hierarchical names, e.g. "parent.child").
    // Auto-detected names are already sanitized to [a-z0-9_], but a user can type
    // anything into the editor's "Field name" box -- an email address being the
    // case that surfaced this.
    private String sanitizeFieldName(String requestedName) {
        return requestedName == null ? null : requestedName.replace('.', '_');
    }
}
