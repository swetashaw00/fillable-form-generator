package com.linkgraph.formfiller.model;

/**
 * One fillable field, in PDF point coordinates (origin bottom-left of the page),
 * either auto-detected or added/edited by hand in the field editor.
 */
public class FieldSpec {

    private String id;
    private int page;
    private double x;
    private double y;
    private double width;
    private double height;
    private FieldType type;
    private String name;
    private boolean required;
    private boolean autoDetected;
    // The exact substring matched in the source document (e.g. "____", "{{email}}",
    // "[Date]"). Only set for auto-detected fields -- it's how the docx generator
    // re-locates this field's position in the original (unrendered) docx, since a
    // flowing Word document has no page x/y coordinates to place a field by.
    private String sourceText;
    // The actual data to fill into this field, e.g. matched from pasted text via
    // TextAutofillService, or typed by hand in the editor. Null/blank means the
    // field stays an empty fillable field (grey placeholder hint only).
    private String value;

    public FieldSpec() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public double getX() {
        return x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double getY() {
        return y;
    }

    public void setY(double y) {
        this.y = y;
    }

    public double getWidth() {
        return width;
    }

    public void setWidth(double width) {
        this.width = width;
    }

    public double getHeight() {
        return height;
    }

    public void setHeight(double height) {
        this.height = height;
    }

    public FieldType getType() {
        return type;
    }

    public void setType(FieldType type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public boolean isAutoDetected() {
        return autoDetected;
    }

    public void setAutoDetected(boolean autoDetected) {
        this.autoDetected = autoDetected;
    }

    public String getSourceText() {
        return sourceText;
    }

    public void setSourceText(String sourceText) {
        this.sourceText = sourceText;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
