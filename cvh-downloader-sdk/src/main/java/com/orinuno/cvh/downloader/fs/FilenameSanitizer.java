package com.orinuno.cvh.downloader.fs;

/**
 * Cleans up caller-supplied {@code filenameHint}s before they reach the filesystem. Strips path
 * separators, control characters, leading dots and reserved Windows names; caps length.
 *
 * <p>Pure function — no IO. Caller is still responsible for resolving against a base directory and
 * checking the result stays inside it (see {@link LocalFsDestination#resolveSafe}).
 */
public final class FilenameSanitizer {

    private static final int MAX_LENGTH = 180;
    private static final String FALLBACK = "video";

    private FilenameSanitizer() {}

    public static String sanitize(String raw) {
        if (raw == null) {
            return FALLBACK;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                continue;
            }
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<'
                    || c == '>' || c == '|') {
                sb.append('_');
                continue;
            }
            sb.append(c);
        }
        String trimmed = sb.toString().strip();
        // Strip leading dots — defends against hidden files and against ".." injection.
        while (!trimmed.isEmpty() && trimmed.charAt(0) == '.') {
            trimmed = trimmed.substring(1).strip();
        }
        if (trimmed.isEmpty()) {
            return FALLBACK;
        }
        if (trimmed.length() > MAX_LENGTH) {
            trimmed = trimmed.substring(0, MAX_LENGTH);
        }
        return trimmed;
    }
}
