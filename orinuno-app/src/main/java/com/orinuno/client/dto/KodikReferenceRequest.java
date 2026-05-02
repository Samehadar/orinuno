package com.orinuno.client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Optional query parameters accepted by Kodik's reference endpoints ({@code /translations/v2},
 * {@code /genres}, {@code /countries}, {@code /years}, {@code /qualities/v2}).
 *
 * <p>Verified via live probe on 2026-05-02 (see docs/research/2026-05-02-api-and-decoder-probe.md):
 *
 * <ul>
 *   <li>{@code genres_type} — accepted by {@code /genres} only. Values: {@code anime}, {@code
 *       drama}, {@code all}. Filters the genre list to the kind you care about; without it, Kodik
 *       returns the same set as {@code drama}.
 *   <li>{@code types} — accepted by {@code /translations/v2}, {@code /years}, {@code /countries}
 *       (untested), {@code /qualities/v2} (untested). Values are the same enum as {@code
 *       KodikSearchRequest.types}: {@code anime-serial}, {@code foreign-movie}, {@code
 *       russian-movie}, {@code russian-serial}, {@code foreign-serial}, {@code russian-cartoon},
 *       {@code foreign-cartoon}, {@code soviet-cartoon}, {@code anime-movie}. <b>Empty / blank
 *       value triggers HTTP 500 {@code "Неправильный тип"}</b> — leave it null when you don't want
 *       to filter.
 * </ul>
 *
 * <p>This DTO does NOT include the token — the token is injected by {@link
 * com.orinuno.client.KodikApiClient}'s rate-limited token failover wrapper.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KodikReferenceRequest {

    /** {@code anime} | {@code drama} | {@code all}. Only honoured by {@code /genres}. */
    private String genresType;

    /**
     * Type filter. Only honoured by endpoints that document it. <b>Must be a valid Kodik type or
     * left null</b> — empty / blank values are rejected with HTTP 500.
     */
    private String types;

    /** Custom Lombok builder extension (API-7) — fluent type-shortcut methods. */
    public static class KodikReferenceRequestBuilder {

        /** Backward-compatible String setter — see KodikSearchRequest comment. */
        public KodikReferenceRequestBuilder types(String types) {
            this.types = types;
            return this;
        }

        /** Set a single type (the most common case for reference endpoints). */
        public KodikReferenceRequestBuilder type(KodikType type) {
            this.types = type == null ? null : type.apiValue();
            return this;
        }

        /** Set multiple types comma-joined. */
        public KodikReferenceRequestBuilder types(KodikType... types) {
            this.types = KodikType.csv(types);
            return this;
        }
    }
}
