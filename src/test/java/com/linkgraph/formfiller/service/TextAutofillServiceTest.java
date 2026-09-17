package com.linkgraph.formfiller.service;

import com.linkgraph.formfiller.model.FieldSpec;
import com.linkgraph.formfiller.model.FieldType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TextAutofillServiceTest {

    private final TextAutofillService service = new TextAutofillService();

    @Test
    void matchesLabeledLinesByFuzzyFieldName() {
        List<FieldSpec> fields = List.of(
                field("full_name", FieldType.TEXT),
                field("your_email", FieldType.TEXT),
                field("Phone Number", FieldType.TEXT),
                field("Mailing Address", FieldType.TEXT)
        );
        String text = """
                Name: Jane Smith
                Email: jane.smith@example.com
                Phone: 555-987-6543
                Address: 42 Main St, Springfield
                """;

        service.autofill(fields, text);

        assertEquals("Jane Smith", fields.get(0).getValue());
        assertEquals("jane.smith@example.com", fields.get(1).getValue());
        assertEquals("555-987-6543", fields.get(2).getValue());
        assertEquals("42 Main St, Springfield", fields.get(3).getValue());
    }

    @Test
    void combinesFirstAndLastNameWhenNoCombinedNameLine() {
        List<FieldSpec> fields = List.of(field("full_name", FieldType.TEXT));
        String text = "First Name: John\nLast Name: Doe\n";

        service.autofill(fields, text);

        assertEquals("John Doe", fields.get(0).getValue());
    }

    @Test
    void fallsBackToRegexForUnlabeledEmail() {
        List<FieldSpec> fields = List.of(field("email", FieldType.TEXT));
        String text = "Reach me any time at contact@example.org for questions.";

        service.autofill(fields, text);

        assertEquals("contact@example.org", fields.get(0).getValue());
    }

    @Test
    void leavesUnmatchedAndSignatureFieldsAlone() {
        List<FieldSpec> fields = List.of(
                field("favorite_color", FieldType.TEXT),
                field("signature", FieldType.SIGNATURE)
        );
        String text = "Name: Jane Smith\nSignature: Jane Smith\n";

        service.autofill(fields, text);

        assertNull(fields.get(0).getValue());
        assertNull(fields.get(1).getValue());
    }

    @Test
    void parsesTabSeparatedTablePaste() {
        List<FieldSpec> fields = List.of(field("full_name", FieldType.TEXT), field("email", FieldType.TEXT));
        String text = "Name\tJane Smith\nEmail\tjane.smith@example.com\n";

        service.autofill(fields, text);

        assertEquals("Jane Smith", fields.get(0).getValue());
        assertEquals("jane.smith@example.com", fields.get(1).getValue());
    }

    @Test
    void parsesMultiSpaceSeparatedTablePaste() {
        List<FieldSpec> fields = List.of(field("full_name", FieldType.TEXT));
        String text = "Name        Jane Smith\n";

        service.autofill(fields, text);

        assertEquals("Jane Smith", fields.get(0).getValue());
    }

    @Test
    void matchesTradingNameAndBareNameFieldsToCompanyName() {
        List<FieldSpec> fields = List.of(
                field("Trading Name", FieldType.TEXT),
                field("Name", FieldType.TEXT)
        );
        String text = "Company Name: Acme Trading Co\n";

        service.autofill(fields, text);

        assertEquals("Acme Trading Co", fields.get(0).getValue());
        assertEquals("Acme Trading Co", fields.get(1).getValue());
    }

    private static FieldSpec field(String name, FieldType type) {
        FieldSpec spec = new FieldSpec();
        spec.setName(name);
        spec.setType(type);
        return spec;
    }
}
