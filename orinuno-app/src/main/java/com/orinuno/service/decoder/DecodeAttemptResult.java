package com.orinuno.service.decoder;

import java.util.Map;

/**
 * DECODE-8 — output of {@link KodikDecodeOrchestrator#decode(String)}: which decoder produced the
 * result, plus the quality → URL map ({@link Map#of()} when nothing worked).
 *
 * <p>Empty map means "no decoder produced a usable URL" — caller must NOT update {@code mp4_link}
 * in that case (overwriting a valid prior link with NULL is the regression we hardened against in
 * the {@code COALESCE(VALUES(mp4_link), mp4_link)} pattern).
 */
public record DecodeAttemptResult(DecodeMethod method, Map<String, String> qualities) {

    public static DecodeAttemptResult regex(Map<String, String> qualities) {
        return new DecodeAttemptResult(DecodeMethod.REGEX, qualities);
    }

    public static DecodeAttemptResult sniff(Map<String, String> qualities) {
        return new DecodeAttemptResult(DecodeMethod.SNIFF, qualities);
    }

    public boolean isEmpty() {
        return qualities == null || qualities.isEmpty();
    }
}
