# fillable-form-generator

A Spring Boot service that turns uploaded PDF/DOCX documents into fillable
forms: it detects candidate fields, lets you edit/autofill them from pasted
text, then generates a fillable PDF (and, for DOCX uploads, a fillable DOCX)
for download.

## Requirements

- **Java 17** (the Gradle toolchain will fetch a matching JDK automatically if
  one isn't already installed)
- **LibreOffice** — required only for `.docx` uploads, which are converted to
  PDF via a headless `soffice` process before field detection.
  - Windows: `winget install TheDocumentFoundation.LibreOffice`
  - macOS: `brew install --cask libreoffice`
  - Linux: install `libreoffice` via your package manager
  - `.pdf` uploads work without LibreOffice installed.

No separate database is required; document sessions are held in memory and
their working files are written under a temp directory (see Configuration).

## Setup

```bash
git clone https://github.com/swetashaw00/fillable-form-generator.git
cd fillable-form-generator

# Run the app (downloads dependencies + Gradle on first run)
./gradlew bootRun        # Windows: gradlew.bat bootRun
```

The app starts on **http://localhost:8080** — open it in a browser for the
upload/editor UI (`src/main/resources/static/`).

## Configuration

Settings live in `src/main/resources/application.properties`:

| Property | Default | Purpose |
|---|---|---|
| `server.port` | `8080` | HTTP port |
| `spring.servlet.multipart.max-file-size` | `25MB` | Max upload size |
| `spring.servlet.multipart.max-request-size` | `25MB` | Max request size |
| `app.storage.dir` | `${java.io.tmpdir}/fillable-form-generator` | Where uploaded/converted/generated files are stored |
| `app.libreoffice.path` | *(empty — auto-detected)* | Set this if `soffice`/`soffice.exe` isn't on `PATH` or in a default install location |

Override any of these via `--define`/env vars, e.g.:

```bash
./gradlew bootRun --args='--server.port=9090'
```

## API

All endpoints are under `/api/documents`:

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/` | Upload a `.pdf` or `.docx` (multipart `file`); returns detected fields |
| `GET` | `/{id}` | Session details (filename, page count, fields) |
| `GET` | `/{id}/pdf` | The working PDF for preview |
| `GET` | `/{id}/fields` | Current field list |
| `PUT` | `/{id}/fields` | Update field definitions |
| `POST` | `/{id}/autofill` | Autofill fields by matching against pasted text |
| `POST` | `/{id}/generate` | Generate the fillable PDF (and DOCX, if uploaded as DOCX) |
| `GET` | `/{id}/download` | Download the generated fillable PDF |
| `GET` | `/{id}/download-docx` | Download the generated fillable DOCX |

## Development

```bash
./gradlew build     # compile + run tests
./gradlew test       # tests only
```

Build artifacts (`build/`, `.gradle/`) and logs are gitignored.
