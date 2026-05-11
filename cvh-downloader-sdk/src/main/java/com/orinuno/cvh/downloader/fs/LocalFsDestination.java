package com.orinuno.cvh.downloader.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * NIO2 helpers for the downloader's filesystem output. Centralises path safety so every callsite
 * goes through {@link #resolveSafe} — paths that would escape the base directory throw.
 */
public final class LocalFsDestination {

    private final Path baseDir;

    public LocalFsDestination(Path baseDir) {
        if (baseDir == null) {
            throw new IllegalArgumentException("baseDir is required");
        }
        this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    public Path baseDir() {
        return baseDir;
    }

    /**
     * Resolves {@code relative} against the base dir and asserts the result stays inside it.
     * Defends against {@code ../../etc/passwd}-style traversal even if {@link FilenameSanitizer} is
     * bypassed.
     */
    public Path resolveSafe(String relative) {
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("relative path must not be blank");
        }
        Path candidate = baseDir.resolve(relative).normalize();
        if (!candidate.startsWith(baseDir)) {
            throw new IllegalArgumentException(
                    "Resolved path escapes base directory: " + candidate);
        }
        return candidate;
    }

    public Path ensureDir(Path dir) throws IOException {
        Files.createDirectories(dir);
        return dir;
    }

    /**
     * Moves {@code from} to {@code to} atomically when supported by the underlying FS. Used to
     * commit a fully-written temp file into the final filename so partial files never appear at the
     * destination path.
     */
    public Path commit(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        try {
            return Files.move(
                    from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (UnsupportedOperationException | java.nio.file.AtomicMoveNotSupportedException ex) {
            return Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }
}
