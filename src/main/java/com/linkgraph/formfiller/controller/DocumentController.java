package com.linkgraph.formfiller.controller;

import com.linkgraph.formfiller.model.DocumentSession;
import com.linkgraph.formfiller.model.FieldSpec;
import com.linkgraph.formfiller.service.DocxFormBuilderService;
import com.linkgraph.formfiller.service.DocxToPdfConverterService;
import com.linkgraph.formfiller.service.FieldDetectionService;
import com.linkgraph.formfiller.service.FormBuilderService;
import com.linkgraph.formfiller.service.TextAutofillService;
import com.linkgraph.formfiller.store.DocumentSessionStore;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final DocumentSessionStore sessionStore;
    private final DocxToPdfConverterService docxToPdfConverterService;
    private final FieldDetectionService fieldDetectionService;
    private final FormBuilderService formBuilderService;
    private final DocxFormBuilderService docxFormBuilderService;
    private final TextAutofillService textAutofillService;

    public DocumentController(DocumentSessionStore sessionStore,
                               DocxToPdfConverterService docxToPdfConverterService,
                               FieldDetectionService fieldDetectionService,
                               FormBuilderService formBuilderService,
                               DocxFormBuilderService docxFormBuilderService,
                               TextAutofillService textAutofillService) {
        this.sessionStore = sessionStore;
        this.docxToPdfConverterService = docxToPdfConverterService;
        this.fieldDetectionService = fieldDetectionService;
        this.formBuilderService = formBuilderService;
        this.docxFormBuilderService = docxFormBuilderService;
        this.textAutofillService = textAutofillService;
    }

    @PostMapping
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No file provided"));
        }
        String originalFilename = file.getOriginalFilename() == null ? "document" : file.getOriginalFilename();
        String extension = extensionOf(originalFilename);
        if (!extension.equals("pdf") && !extension.equals("docx")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only .pdf and .docx files are supported"));
        }

        String sessionId = UUID.randomUUID().toString();
        Path sessionDir = sessionStore.newSessionDir(sessionId);
        Path uploadedFile = sessionDir.resolve("upload." + extension);
        file.transferTo(uploadedFile);

        Path workingPdf;
        Path originalDocxPath = null;
        if (extension.equals("docx")) {
            workingPdf = docxToPdfConverterService.convertToPdf(uploadedFile, sessionDir);
            originalDocxPath = uploadedFile;
        } else {
            workingPdf = sessionDir.resolve("working.pdf");
            Files.copy(uploadedFile, workingPdf);
        }

        DocumentSession session = new DocumentSession(sessionId, originalFilename, workingPdf, originalDocxPath);
        List<FieldSpec> detectedFields;
        try (PDDocument document = Loader.loadPDF(workingPdf.toFile())) {
            session.setPageCount(document.getNumberOfPages());
            detectedFields = fieldDetectionService.detect(document);
        }
        session.setFields(detectedFields);
        sessionStore.put(session);

        return ResponseEntity.ok(Map.of(
                "id", session.getId(),
                "originalFilename", session.getOriginalFilename(),
                "pageCount", session.getPageCount(),
                "fields", session.getFields()
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        DocumentSession session = sessionStore.get(id);
        return ResponseEntity.ok(Map.of(
                "id", session.getId(),
                "originalFilename", session.getOriginalFilename(),
                "pageCount", session.getPageCount(),
                "fields", session.getFields()
        ));
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<FileSystemResource> pdf(@PathVariable String id) {
        DocumentSession session = sessionStore.get(id);
        FileSystemResource resource = new FileSystemResource(session.getWorkingPdfPath());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .body(resource);
    }

    @GetMapping("/{id}/fields")
    public ResponseEntity<List<FieldSpec>> getFields(@PathVariable String id) {
        return ResponseEntity.ok(sessionStore.get(id).getFields());
    }

    @PutMapping("/{id}/fields")
    public ResponseEntity<?> updateFields(@PathVariable String id, @RequestBody List<FieldSpec> fields) {
        DocumentSession session = sessionStore.get(id);
        session.setFields(fields);
        return ResponseEntity.ok(Map.of("saved", fields.size()));
    }

    @PostMapping("/{id}/autofill")
    public ResponseEntity<?> autofill(@PathVariable String id, @RequestBody Map<String, String> body) {
        DocumentSession session = sessionStore.get(id);
        String text = body.getOrDefault("text", "");
        List<FieldSpec> filled = textAutofillService.autofill(session.getFields(), text);
        session.setFields(filled);

        // Deliberately logs field NAMES and match outcome only -- never the
        // pasted text or the matched values -- so this is safe to inspect
        // without exposing whatever personal/business data was pasted in.
        StringBuilder summary = new StringBuilder();
        for (FieldSpec field : filled) {
            summary.append(field.getName()).append('=')
                    .append(field.getValue() != null && !field.getValue().isBlank() ? "matched" : "unmatched")
                    .append("; ");
        }
        log.info("Autofill session={} pastedChars={} fields=[{}]", id, text.length(), summary);

        return ResponseEntity.ok(Map.of("fields", filled));
    }

    @PostMapping("/{id}/generate")
    public ResponseEntity<?> generate(@PathVariable String id) throws IOException {
        DocumentSession session = sessionStore.get(id);
        Path outputDir = session.getWorkingPdfPath().getParent();
        Path generatedPdf = outputDir.resolve("generated.pdf");
        formBuilderService.buildFillablePdf(session.getWorkingPdfPath(), session.getFields(), generatedPdf);
        session.setGeneratedPdfPath(generatedPdf);

        Map<String, Object> response = new java.util.HashMap<>();
        response.put("pdfDownloadUrl", "/api/documents/" + id + "/download");

        if (session.getOriginalDocxPath() != null) {
            Path generatedDocx = outputDir.resolve("generated.docx");
            DocxFormBuilderService.DocxGenerationResult result = docxFormBuilderService.buildFillableDocx(
                    session.getOriginalDocxPath(), session.getFields(), generatedDocx);
            session.setGeneratedDocxPath(generatedDocx);
            response.put("docxDownloadUrl", "/api/documents/" + id + "/download-docx");
            response.put("docxConvertedFields", result.convertedFieldNames());
            response.put("docxSkippedFields", result.skippedFieldNames());
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable String id) {
        DocumentSession session = sessionStore.get(id);
        if (session.getGeneratedPdfPath() == null || !Files.exists(session.getGeneratedPdfPath())) {
            throw new NoSuchElementException("Fillable PDF has not been generated yet for session " + id);
        }
        String downloadName = baseName(session.getOriginalFilename()) + "-fillable.pdf";
        FileSystemResource resource = new FileSystemResource(session.getGeneratedPdfPath());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadName + "\"")
                .body(resource);
    }

    @GetMapping("/{id}/download-docx")
    public ResponseEntity<FileSystemResource> downloadDocx(@PathVariable String id) {
        DocumentSession session = sessionStore.get(id);
        if (session.getGeneratedDocxPath() == null || !Files.exists(session.getGeneratedDocxPath())) {
            throw new NoSuchElementException("Fillable DOCX has not been generated yet for session " + id);
        }
        String downloadName = baseName(session.getOriginalFilename()) + "-fillable.docx";
        FileSystemResource resource = new FileSystemResource(session.getGeneratedDocxPath());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadName + "\"")
                .body(resource);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(DocxToPdfConverterService.DocxConversionException.class)
    public ResponseEntity<Map<String, String>> handleConversionFailure(
            DocxToPdfConverterService.DocxConversionException e) {
        return ResponseEntity.status(422).body(Map.of("error", e.getMessage()));
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String baseName(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }
}
