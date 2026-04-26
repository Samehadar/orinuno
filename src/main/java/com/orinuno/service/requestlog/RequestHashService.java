package com.orinuno.service.requestlog;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.orinuno.model.dto.ParseRequestDto;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Computes a stable canonical-JSON SHA-256 hash for a {@link ParseRequestDto}. Hash is used to
 * deduplicate active (PENDING/RUNNING) parse requests.
 */
@Service
public class RequestHashService {

    private final ObjectMapper canonicalMapper =
            JsonMapper.builder()
                    .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .serializationInclusion(JsonInclude.Include.NON_NULL)
                    .build();

    public Result compute(ParseRequestDto request) {
        Map<String, Object> normalized = normalize(request);
        String canonicalJson;
        try {
            canonicalJson = canonicalMapper.writeValueAsString(normalized);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to canonicalize request", e);
        }
        return new Result(sha256Hex(canonicalJson), canonicalJson);
    }

    private Map<String, Object> normalize(ParseRequestDto request) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("title", normalizeString(request.getTitle()));
        map.put("kinopoiskId", normalizeId(request.getKinopoiskId()));
        map.put("imdbId", normalizeId(request.getImdbId()));
        map.put("shikimoriId", normalizeId(request.getShikimoriId()));
        map.put("decodeLinks", request.isDecodeLinks());
        map.values().removeIf(v -> v == null);
        return map;
    }

    private static String normalizeString(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.toLowerCase();
    }

    private static String normalizeId(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public record Result(String hash, String canonicalJson) {}
}
