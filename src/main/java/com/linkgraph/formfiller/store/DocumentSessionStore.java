package com.linkgraph.formfiller.store;

import com.linkgraph.formfiller.model.DocumentSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of active document sessions, backed by a per-session
 * working directory on disk (uploaded/converted/generated PDFs).
 */
@Component
public class DocumentSessionStore {

    private final ConcurrentHashMap<String, DocumentSession> sessions = new ConcurrentHashMap<>();
    private final Path storageRoot;

    public DocumentSessionStore(@Value("${app.storage.dir}") String storageDir) {
        this.storageRoot = Path.of(storageDir);
        try {
            Files.createDirectories(storageRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create storage directory: " + storageRoot, e);
        }
    }

    public Path newSessionDir(String sessionId) {
        try {
            Path dir = storageRoot.resolve(sessionId);
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            throw new IllegalStateException("Could not create session directory for " + sessionId, e);
        }
    }

    public void put(DocumentSession session) {
        sessions.put(session.getId(), session);
    }

    public DocumentSession get(String id) {
        return Optional.ofNullable(sessions.get(id))
                .orElseThrow(() -> new NoSuchElementException("No document session: " + id));
    }
}
