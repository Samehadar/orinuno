package com.orinuno.service;

import com.orinuno.client.embed.KodikEmbedException;
import com.orinuno.client.embed.KodikEmbedHttpClient;
import com.orinuno.client.embed.KodikIdType;
import com.orinuno.model.dto.EmbedLinkDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Resolves a Kodik embed (iframe) URL by external id ({@code shikimori_id}, {@code kinopoisk_id},
 * {@code imdb_id}, …). Wraps {@link KodikEmbedHttpClient} with response interpretation and URL
 * normalisation.
 *
 * <p>Distinct from {@code ParserService} on purpose — this is the lightweight "give me a player
 * iframe URL" path that does NOT touch the database, the parser pipeline, or the video decoder. It
 * is a thin convenience layer over Kodik's {@code /get-player} helper for callers (admin UI,
 * external embedders, IDEA-CAL-3 widget) who only need the iframe URL itself.
 *
 * <p>Failure mapping:
 *
 * <ul>
 *   <li>Kodik {@code "found": false} → {@link KodikEmbedException.NotFoundException}
 *   <li>Kodik {@code "error": ...} (non-token) → {@link KodikEmbedException.UpstreamException}
 *   <li>Missing {@code link} field with {@code found=true} → {@link
 *       KodikEmbedException.MalformedResponseException}
 *   <li>Token errors propagate as {@link com.orinuno.token.KodikTokenException} subtypes from the
 *       HTTP client.
 * </ul>
 */
@Slf4j
@Service
public class KodikEmbedService {

    private static final String KODIK_PLAYER_HOST = "kodikplayer.com/";

    private final KodikEmbedHttpClient httpClient;

    public KodikEmbedService(KodikEmbedHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Resolve the embed URL for the supplied external id. Normalisation (e.g. {@code tt}-prefixing
     * imdb ids) happens here via {@link KodikIdType#normalizeId(String)}.
     *
     * <p>All input validation runs inside {@link Mono#defer} so callers see a uniform reactive
     * error signal — a blank id surfaces as {@code Mono.error(IllegalArgumentException)}, never as
     * a synchronous exception thrown out of {@code resolve(...)}. This matters for the controller
     * layer, which only wires {@code onErrorResume} handlers.
     */
    public Mono<EmbedLinkDto> resolve(KodikIdType idType, String requestedId) {
        return Mono.defer(
                () -> {
                    String normalizedId = idType.normalizeId(requestedId);
                    return httpClient
                            .getPlayerRaw(idType, normalizedId)
                            .flatMap(
                                    raw ->
                                            Mono.fromCallable(
                                                    () ->
                                                            interpret(
                                                                    raw,
                                                                    idType,
                                                                    requestedId,
                                                                    normalizedId)));
                });
    }

    private static EmbedLinkDto interpret(
            java.util.Map<String, Object> raw,
            KodikIdType idType,
            String requestedId,
            String normalizedId) {
        if (raw == null) {
            throw new KodikEmbedException.MalformedResponseException(
                    "Kodik /get-player returned an empty body");
        }
        Object error = raw.get("error");
        if (error != null) {
            throw new KodikEmbedException.UpstreamException("Kodik /get-player error: " + error);
        }
        Object found = raw.get("found");
        if (Boolean.FALSE.equals(found)) {
            throw new KodikEmbedException.NotFoundException(
                    "Kodik has no player for "
                            + idType.getSlug()
                            + ":"
                            + requestedId
                            + " (normalised: "
                            + normalizedId
                            + ")");
        }
        Object link = raw.get("link");
        if (!(link instanceof String linkStr) || linkStr.isBlank()) {
            throw new KodikEmbedException.MalformedResponseException(
                    "Kodik /get-player returned no 'link' field for "
                            + idType.getSlug()
                            + ":"
                            + requestedId);
        }
        String embedUrl = normalizeEmbedUrl(linkStr);
        String mediaType = detectMediaType(embedUrl);
        log.debug(
                "Kodik /get-player resolved {}:{} → {} (mediaType={})",
                idType.getSlug(),
                requestedId,
                embedUrl,
                mediaType);
        return new EmbedLinkDto(idType, requestedId, normalizedId, embedUrl, mediaType);
    }

    /**
     * Normalise the {@code link} field Kodik returns. Kodik may emit {@code //kodikplayer.com/...}
     * (protocol-relative) or {@code https://kodikplayer.com/...}; we always produce {@code
     * https://} so callers don't need to reason about it.
     */
    static String normalizeEmbedUrl(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("//")) {
            return "https:" + trimmed;
        }
        if (trimmed.startsWith("http://")) {
            return "https://" + trimmed.substring("http://".length());
        }
        if (trimmed.startsWith("https://")) {
            return trimmed;
        }
        return "https://" + trimmed;
    }

    /**
     * Match AnimeParsers' {@code _is_serial / _is_video} heuristic — the first character after
     * {@code kodikplayer.com/} determines the kind: {@code s} → serial, {@code v} → video. Returns
     * {@code null} for anything else so the caller can fall back to "treat as opaque link".
     */
    static String detectMediaType(String embedUrl) {
        int idx = embedUrl.indexOf(KODIK_PLAYER_HOST);
        if (idx < 0) {
            return null;
        }
        int kindIdx = idx + KODIK_PLAYER_HOST.length();
        if (kindIdx >= embedUrl.length()) {
            return null;
        }
        char first = embedUrl.charAt(kindIdx);
        return switch (first) {
            case 's' -> "serial";
            case 'v' -> "video";
            default -> null;
        };
    }
}
