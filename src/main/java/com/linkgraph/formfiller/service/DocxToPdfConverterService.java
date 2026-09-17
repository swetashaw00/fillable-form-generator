package com.linkgraph.formfiller.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Converts an uploaded .docx to .pdf using a headless LibreOffice process.
 * LibreOffice must be installed (winget install TheDocumentFoundation.LibreOffice
 * on Windows) since there is no reliable pure-Java equivalent with comparable fidelity.
 */
@Service
public class DocxToPdfConverterService {

    private static final Duration CONVERSION_TIMEOUT = Duration.ofSeconds(60);

    private final String configuredSofficePath;

    public DocxToPdfConverterService(@Value("${app.libreoffice.path:}") String configuredSofficePath) {
        this.configuredSofficePath = configuredSofficePath;
    }

    public Path convertToPdf(Path docxFile, Path outputDir) {
        String soffice = resolveSofficeExecutable();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    soffice,
                    "--headless",
                    "--norestore",
                    "--convert-to", "pdf",
                    "--outdir", outputDir.toAbsolutePath().toString(),
                    docxFile.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (var in = process.getInputStream()) {
                output = new String(in.readAllBytes());
            }

            boolean finished = process.waitFor(CONVERSION_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new DocxConversionException("LibreOffice conversion timed out after "
                        + CONVERSION_TIMEOUT.getSeconds() + "s");
            }
            if (process.exitValue() != 0) {
                throw new DocxConversionException("LibreOffice conversion failed (exit "
                        + process.exitValue() + "): " + output);
            }

            String expectedName = stripExtension(docxFile.getFileName().toString()) + ".pdf";
            Path converted = outputDir.resolve(expectedName);
            if (!Files.exists(converted)) {
                throw new DocxConversionException(
                        "LibreOffice reported success but no output PDF was found at " + converted
                                + ". Output: " + output);
            }
            return converted;
        } catch (IOException e) {
            throw new DocxConversionException("Could not start LibreOffice (checked: " + soffice + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DocxConversionException("Interrupted while waiting for LibreOffice conversion", e);
        }
    }

    private String resolveSofficeExecutable() {
        if (configuredSofficePath != null && !configuredSofficePath.isBlank()) {
            return configuredSofficePath;
        }
        List<Path> candidates = List.of(
                Path.of("C:/Program Files/LibreOffice/program/soffice.exe"),
                Path.of("C:/Program Files (x86)/LibreOffice/program/soffice.exe")
        );
        Optional<Path> found = candidates.stream().filter(Files::exists).findFirst();
        // Fall back to "soffice" on PATH (covers Linux/macOS and custom Windows installs).
        return found.map(Path::toString).orElse("soffice");
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }

    public static class DocxConversionException extends RuntimeException {
        public DocxConversionException(String message) {
            super(message);
        }

        public DocxConversionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
