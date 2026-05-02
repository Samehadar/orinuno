package com.orinuno.model.dto;

import com.orinuno.client.embed.KodikIdType;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response payload for {@code GET /api/v1/embed/{idType}/{id}}. Contains everything a downstream
 * consumer needs to embed a Kodik iframe without re-resolving the id.
 *
 * @param idType the id type as provided in the request path (canonical {@code snake_case} slug)
 * @param requestedId the id as supplied by the caller (before normalisation)
 * @param normalizedId the id actually sent to Kodik — for {@link KodikIdType#IMDB} this is the
 *     {@code tt}-prefixed form
 * @param embedLink absolute {@code https://} URL of the player iframe ({@code
 *     https://kodikplayer.com/serial/…/720p}). Always normalised to {@code https} regardless of how
 *     Kodik returned it.
 * @param mediaType {@code "serial"} for multi-episode content, {@code "video"} for single videos /
 *     films, {@code null} when the link does not match the expected {@code kodikplayer.com} pattern
 *     (treated as soft signal — caller should still treat the link as opaque)
 */
@Schema(description = "Resolved Kodik embed (iframe) URL plus metadata for the requested id.")
public record EmbedLinkDto(
        @Schema(example = "shikimori") KodikIdType idType,
        @Schema(example = "20") String requestedId,
        @Schema(example = "20") String normalizedId,
        @Schema(
                        example =
                                "https://kodikplayer.com/serial/73959/68e2e57cb95f7fb93655637acaca26c2/720p")
                String embedLink,
        @Schema(example = "serial", nullable = true) String mediaType) {}
