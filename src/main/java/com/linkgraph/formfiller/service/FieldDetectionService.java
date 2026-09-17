package com.linkgraph.formfiller.service;

import com.linkgraph.formfiller.model.FieldSpec;
import com.linkgraph.formfiller.model.FieldType;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans a PDF's text layer for common "blank to fill in" conventions and turns
 * each match into a suggested FieldSpec (page + PDF-point bounding box). These
 * are starting points the user can move, resize, retype or delete in the
 * field editor -- not final placements.
 */
@Service
public class FieldDetectionService {

    // Alternation order matters: CHECKBOX must be tried before BRACKET so that
    // "[ ]" / "[]" is claimed as a checkbox and not as an empty-label text field.
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile(
            "(?<CHECKBOX>\\[\\s?]|[☐❑])"
                    + "|(?<MUSTACHE>\\{\\{\\s*([A-Za-z0-9_ \\-]{1,40}?)\\s*}})"
                    + "|(?<BRACKET>\\[\\s*([A-Za-z0-9_ \\-/]{1,40}?)\\s*])"
                    + "|(?<UNDERSCORE>_{3,})"
    );

    // Underscore glyphs sit near the baseline and are visually flat, so a field
    // box sized to their raw glyph height would be a sliver; give it a usable height instead.
    private static final double MIN_FIELD_HEIGHT = 12.0;
    private static final double FIELD_PADDING = 2.0;

    // Images smaller than this (in PDF points, either dimension) are almost always
    // bullets/icons/letterhead flourishes rather than an intentional signature/photo
    // box, so they're excluded from the auto-detect suggestions to cut down noise.
    private static final double MIN_IMAGE_DIMENSION = 40.0;

    public List<FieldSpec> detect(PDDocument document) throws IOException {
        List<FieldSpec> results = new ArrayList<>();
        PositionAwareStripper stripper = new PositionAwareStripper(results);
        stripper.setSortByPosition(true);
        stripper.getText(document);

        int imageCounter = 0;
        for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
            PDPage page = document.getPage(pageIndex);
            ImageRegionFinder finder = new ImageRegionFinder();
            finder.processPage(page);
            for (PDRectangle region : finder.getImageRegions()) {
                if (region.getWidth() < MIN_IMAGE_DIMENSION || region.getHeight() < MIN_IMAGE_DIMENSION) {
                    continue;
                }
                imageCounter++;
                FieldSpec field = new FieldSpec();
                field.setId(UUID.randomUUID().toString());
                field.setPage(pageIndex);
                field.setX(region.getLowerLeftX());
                field.setY(region.getLowerLeftY());
                field.setWidth(region.getWidth());
                field.setHeight(region.getHeight());
                field.setAutoDetected(true);
                field.setType(FieldType.SIGNATURE);
                field.setName(sanitizeName(null, "image_field", imageCounter));
                // No sourceText: an image region has no literal text anchor, so this
                // suggestion can't be carried over into a generated fillable docx.
                results.add(field);
            }
        }
        return results;
    }

    private static String sanitizeName(String raw, String fallbackPrefix, int counter) {
        if (raw == null || raw.isBlank()) {
            return fallbackPrefix + "_" + counter;
        }
        String cleaned = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        cleaned = cleaned.replaceAll("^_+|_+$", "");
        return cleaned.isBlank() ? fallbackPrefix + "_" + counter : cleaned;
    }

    private class PositionAwareStripper extends PDFTextStripper {

        private final List<FieldSpec> results;
        private int pageIndex = -1;
        private double pageHeight;
        private int anonCounter = 0;

        PositionAwareStripper(List<FieldSpec> results) throws IOException {
            this.results = results;
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            pageIndex++;
            pageHeight = page.getMediaBox().getHeight();
            super.startPage(page);
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
            while (matcher.find()) {
                if (matcher.start() == matcher.end() || textPositions.isEmpty()) {
                    continue;
                }
                int start = Math.min(matcher.start(), textPositions.size() - 1);
                int end = Math.min(matcher.end(), textPositions.size());
                if (start >= end) {
                    continue;
                }

                double minX = Double.MAX_VALUE;
                double maxX = -Double.MAX_VALUE;
                double minTop = Double.MAX_VALUE;
                double maxBaseline = -Double.MAX_VALUE;
                for (int i = start; i < end; i++) {
                    TextPosition tp = textPositions.get(i);
                    minX = Math.min(minX, tp.getX());
                    maxX = Math.max(maxX, tp.getX() + tp.getWidth());
                    minTop = Math.min(minTop, tp.getY() - tp.getHeight());
                    maxBaseline = Math.max(maxBaseline, tp.getY());
                }

                double llx = minX - FIELD_PADDING;
                double urx = maxX + FIELD_PADDING;
                double ury = pageHeight - minTop + FIELD_PADDING;
                double lly = pageHeight - maxBaseline - FIELD_PADDING;
                double width = Math.max(urx - llx, 20);
                double height = Math.max(ury - lly, MIN_FIELD_HEIGHT);

                anonCounter++;
                FieldSpec field = new FieldSpec();
                field.setId(UUID.randomUUID().toString());
                field.setPage(pageIndex);
                field.setX(llx);
                field.setY(lly);
                field.setWidth(width);
                field.setHeight(height);
                field.setAutoDetected(true);
                field.setSourceText(matcher.group());

                if (matcher.group("CHECKBOX") != null) {
                    field.setType(FieldType.CHECKBOX);
                    field.setName(sanitizeName(null, "checkbox", anonCounter));
                } else if (matcher.group("MUSTACHE") != null) {
                    field.setType(FieldType.TEXT);
                    field.setName(sanitizeName(matcher.group(3), "field", anonCounter));
                } else if (matcher.group("BRACKET") != null) {
                    field.setType(FieldType.TEXT);
                    field.setName(sanitizeName(matcher.group(5), "field", anonCounter));
                } else {
                    field.setType(FieldType.TEXT);
                    field.setName(sanitizeName(null, "field", anonCounter));
                }

                results.add(field);
            }
            super.writeString(text, textPositions);
        }
    }

    /**
     * Walks a page's content stream and records the placement rectangle of every
     * drawn raster image (the "Do" operator painting a PDImageXObject), using the
     * current transformation matrix at the point it's painted. Rotated/skewed
     * placements are approximated by their axis-aligned bounding box.
     */
    private static class ImageRegionFinder extends PDFStreamEngine {

        private final List<PDRectangle> imageRegions = new ArrayList<>();

        List<PDRectangle> getImageRegions() {
            return imageRegions;
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            if ("Do".equals(operator.getName())) {
                COSName name = (COSName) operands.get(0);
                PDXObject xobject = getResources().getXObject(name);
                if (xobject instanceof PDImageXObject) {
                    imageRegions.add(currentImageBounds());
                } else if (xobject instanceof PDFormXObject formXObject) {
                    showForm(formXObject);
                }
            } else {
                super.processOperator(operator, operands);
            }
        }

        private PDRectangle currentImageBounds() {
            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            float[] corners = new float[]{0, 0, 1, 0, 0, 1, 1, 1};
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (int i = 0; i < corners.length; i += 2) {
                var point = ctm.transformPoint(corners[i], corners[i + 1]);
                minX = Math.min(minX, (float) point.getX());
                maxX = Math.max(maxX, (float) point.getX());
                minY = Math.min(minY, (float) point.getY());
                maxY = Math.max(maxY, (float) point.getY());
            }
            PDRectangle rect = new PDRectangle();
            rect.setLowerLeftX(minX);
            rect.setLowerLeftY(minY);
            rect.setUpperRightX(maxX);
            rect.setUpperRightY(maxY);
            return rect;
        }
    }
}
