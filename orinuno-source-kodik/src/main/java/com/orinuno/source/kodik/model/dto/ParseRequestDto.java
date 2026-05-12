/*
 * ParseRequestDto — ADR 0021 §D-prep.
 *
 * Wire format for POST /api/v1/parse/* requests in source-kodik. Ported
 * field-for-field from orinuno-app/.../model/dto/ParseRequestDto so the
 * cutover (D2 reverse-proxy + D1 service migration) is a flat path swap.
 */
package com.orinuno.source.kodik.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParseRequestDto {

    private String title;
    private String kinopoiskId;
    private String imdbId;
    private String shikimoriId;

    /** If true, also decode mp4 links after search. */
    @Builder.Default private boolean decodeLinks = false;

    /**
     * Reject completely empty payloads. At least one of title or any external id must be non-blank,
     * otherwise the request body has no meaningful Kodik query.
     */
    @JsonIgnore
    @AssertTrue(
            message =
                    "ParseRequestDto requires at least one of: title, kinopoiskId, imdbId,"
                            + " shikimoriId")
    public boolean isQueryProvided() {
        return hasText(title) || hasText(kinopoiskId) || hasText(imdbId) || hasText(shikimoriId);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
