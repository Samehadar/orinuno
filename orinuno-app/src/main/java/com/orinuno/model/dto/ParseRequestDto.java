package com.orinuno.model.dto;

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
     * Reject completely empty payloads ({@link com.orinuno.controller.ParseRequestController}
     * applies {@code @Valid}). At least one of title or any external id must be non-blank,
     * otherwise the request body has no meaningful Kodik query and would fan out to a
     * non-deterministic search. This bounds {@code TD-2} (full validation suite is still pending).
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
