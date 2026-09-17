package com.linkgraph.formfiller.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Server-side state for one uploaded document as it moves through
 * upload -> (docx converted to pdf) -> auto-detect -> edit -> generate.
 */
public class DocumentSession {

    private final String id;
    private final String originalFilename;
    private final Path workingPdfPath;
    // Only set when the upload was a .docx -- the source for the fillable-docx
    // generation path, kept separate from workingPdfPath (the LibreOffice-converted render).
    private final Path originalDocxPath;
    private int pageCount;
    private List<FieldSpec> fields = new ArrayList<>();
    private Path generatedPdfPath;
    private Path generatedDocxPath;

    public DocumentSession(String id, String originalFilename, Path workingPdfPath, Path originalDocxPath) {
        this.id = id;
        this.originalFilename = originalFilename;
        this.workingPdfPath = workingPdfPath;
        this.originalDocxPath = originalDocxPath;
    }

    public String getId() {
        return id;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public Path getWorkingPdfPath() {
        return workingPdfPath;
    }

    public int getPageCount() {
        return pageCount;
    }

    public void setPageCount(int pageCount) {
        this.pageCount = pageCount;
    }

    public List<FieldSpec> getFields() {
        return fields;
    }

    public void setFields(List<FieldSpec> fields) {
        this.fields = fields;
    }

    public Path getGeneratedPdfPath() {
        return generatedPdfPath;
    }

    public void setGeneratedPdfPath(Path generatedPdfPath) {
        this.generatedPdfPath = generatedPdfPath;
    }

    public Path getOriginalDocxPath() {
        return originalDocxPath;
    }

    public Path getGeneratedDocxPath() {
        return generatedDocxPath;
    }

    public void setGeneratedDocxPath(Path generatedDocxPath) {
        this.generatedDocxPath = generatedDocxPath;
    }
}
