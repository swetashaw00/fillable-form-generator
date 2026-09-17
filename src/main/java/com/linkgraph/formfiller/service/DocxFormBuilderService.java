package com.linkgraph.formfiller.service;

import com.linkgraph.formfiller.model.FieldSpec;
import com.linkgraph.formfiller.model.FieldType;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.xmlbeans.XmlCursor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtContentRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtRun;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds a fillable .docx by inserting native Word content controls (SDTs) at
 * the position of each auto-detected placeholder found in the ORIGINAL source
 * document -- as opposed to the PDF/AcroForm path, which places fields by
 * fixed page coordinates.
 *
 * Word documents flow rather than having page coordinates, so a field can only
 * be placed here if we can re-locate its literal matched text (FieldSpec.sourceText)
 * in the source docx. Manually drawn fields (added in the visual PDF editor,
 * with no corresponding source text) have nowhere principled to go in a flowing
 * document and are skipped -- reported back so the caller can tell the user.
 */
@Service
public class DocxFormBuilderService {

    public DocxGenerationResult buildFillableDocx(Path originalDocx, List<FieldSpec> fields, Path outputDocx)
            throws IOException {
        List<String> converted = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int idCounter = 1000;

        try (InputStream in = Files.newInputStream(originalDocx);
             XWPFDocument document = new XWPFDocument(in)) {

            List<XWPFParagraph> paragraphs = collectParagraphs(document);

            for (FieldSpec field : fields) {
                if (field.getSourceText() == null || field.getSourceText().isBlank()) {
                    skipped.add(field.getName());
                    continue;
                }
                if (placeField(paragraphs, field, idCounter)) {
                    converted.add(field.getName());
                    idCounter++;
                } else {
                    skipped.add(field.getName());
                }
            }

            try (OutputStream out = Files.newOutputStream(outputDocx)) {
                document.write(out);
            }
        }

        return new DocxGenerationResult(converted, skipped);
    }

    private boolean placeField(List<XWPFParagraph> paragraphs, FieldSpec field, int id) {
        for (XWPFParagraph paragraph : paragraphs) {
            List<XWPFRun> runs = paragraph.getRuns();
            for (int i = 0; i < runs.size(); i++) {
                XWPFRun run = runs.get(i);
                String text = run.getText(0);
                if (text != null && text.contains(field.getSourceText())) {
                    insertContentControl(paragraph, run, i, field, id);
                    return true;
                }
            }
        }
        return false;
    }

    private void insertContentControl(XWPFParagraph paragraph, XWPFRun run, int runIndex, FieldSpec field, int id) {
        String text = run.getText(0);
        int idx = text.indexOf(field.getSourceText());
        String before = text.substring(0, idx);
        String after = text.substring(idx + field.getSourceText().length());

        run.setText(before, 0);

        if (!after.isEmpty()) {
            XWPFRun afterRun = paragraph.insertNewRun(runIndex + 1);
            afterRun.setText(after);
            copyFormatting(run, afterRun);
        }

        // addNewSdt() attaches the new element into the paragraph's own XML tree
        // right away (just at the wrong position, appended at the end) -- unlike
        // CTSdtRun.Factory.newInstance(), which creates a standalone document that
        // XmlCursor.moveXml() refuses to relocate ("can't move a whole document").
        CTSdtRun sdt = paragraph.getCTP().addNewSdt();
        populateSdt(sdt, field, id);

        XmlCursor anchorCursor = run.getCTR().newCursor();
        try {
            anchorCursor.toEndToken();
            anchorCursor.toNextToken();
            XmlCursor sdtCursor = sdt.newCursor();
            try {
                sdtCursor.moveXml(anchorCursor);
            } finally {
                sdtCursor.dispose();
            }
        } finally {
            anchorCursor.dispose();
        }
    }

    private void populateSdt(CTSdtRun sdt, FieldSpec field, int id) {
        CTSdtPr pr = sdt.addNewSdtPr();
        pr.addNewAlias().setVal(field.getName());
        pr.addNewTag().setVal(field.getName());
        pr.addNewId().setVal(BigInteger.valueOf(id));
        pr.addNewText();

        boolean hasValue = field.getValue() != null && !field.getValue().isBlank();
        if (!hasValue) {
            // ShowingPlcHdr marks the content run below as placeholder text (grey,
            // cleared on first click) -- only correct when there's no real value.
            pr.addNewShowingPlcHdr();
        }

        CTSdtContentRun content = sdt.addNewSdtContent();
        CTR contentRun = content.addNewR();
        contentRun.addNewT().setStringValue(contentText(field, hasValue));
    }

    private String contentText(FieldSpec field, boolean hasValue) {
        if (!hasValue) {
            return placeholderLabel(field);
        }
        return field.getType() == FieldType.CHECKBOX
                ? (isChecked(field.getValue()) ? "☒" : "☐")
                : field.getValue();
    }

    private boolean isChecked(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("yes") || normalized.equals("true") || normalized.equals("x")
                || normalized.equals("checked") || normalized.equals("on") || normalized.equals("1");
    }

    private String placeholderLabel(FieldSpec field) {
        return switch (field.getType()) {
            // Toggling remains manual (retype the glyph) -- see class javadoc:
            // a real checkbox content control needs the w14 namespace extension,
            // which this build intentionally doesn't depend on.
            case CHECKBOX -> "☐ (replace with ☒ to check)";
            case DATE -> "Click to enter a date.";
            case SIGNATURE -> "Sign here";
            case TEXT -> "Click or tap here to enter text.";
        };
    }

    private void copyFormatting(XWPFRun from, XWPFRun to) {
        to.setBold(from.isBold());
        to.setItalic(from.isItalic());
        if (from.getFontFamily() != null) {
            to.setFontFamily(from.getFontFamily());
        }
        if (from.getFontSize() != -1) {
            to.setFontSize(from.getFontSize());
        }
        if (from.getColor() != null) {
            to.setColor(from.getColor());
        }
    }

    private List<XWPFParagraph> collectParagraphs(XWPFDocument document) {
        List<XWPFParagraph> paragraphs = new ArrayList<>();
        collectFromBodyElements(document.getBodyElements(), paragraphs);
        return paragraphs;
    }

    private void collectFromBodyElements(List<IBodyElement> elements, List<XWPFParagraph> out) {
        for (IBodyElement element : elements) {
            if (element instanceof XWPFParagraph paragraph) {
                out.add(paragraph);
            } else if (element instanceof XWPFTable table) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        collectFromBodyElements(cell.getBodyElements(), out);
                    }
                }
            }
        }
    }

    public record DocxGenerationResult(List<String> convertedFieldNames, List<String> skippedFieldNames) {
    }
}
