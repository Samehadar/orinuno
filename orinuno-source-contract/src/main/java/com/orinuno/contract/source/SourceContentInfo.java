package com.orinuno.contract.source;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Objects;

/**
 * Title-level metadata observed from an upstream source: localised titles, year, kind hint,
 * external ids, and producer-side URLs for posters/screenshots/trailers. Mirrors meter's {@code
 * ContentCommonInfo} (see {@code
 * downstream-repo/meter-api-spring-boot-starter/.../ContentCommonInfo.java}) but stripped of
 * Kin-specific value-object wrappers and closed enums.
 *
 * <p>What this record carries vs. what it deliberately does not:
 *
 * <ul>
 *   <li>{@link #posterUrl}, {@link #bigPosterUrl}, {@link #screenshotUrls}, {@link #trailerUrls} —
 *       <em>fully-qualified producer-side URLs</em>. Open-source consumers can render them
 *       directly. Kin-side consumers ({@code kodik-parser} → {@code external-bridge}) download
 *       them into MinIO and translate the resulting object keys into meter's {@code posterFilepath}
 *       / {@code bigPosterFilepath} / {@code trailerFilepaths} family. This is the shape promised
 *       by ADR 0017 §"Audit table — meter contract → orinuno-source-contract" and closes
 *       ARCH-0017-FOLLOWUP-POSTER.
 *   <li>{@code mediaUrl} for episode/movie playback lives on {@link SourceEpisodeVariant} (as it
 *       always did) — these fields are content-level chrome, not playable streams.
 *   <li>Audio/video quality enums stay as plain strings on the variant when relevant; this record
 *       does not duplicate them.
 * </ul>
 *
 * <p>{@link #titleRu} and {@link #titleEn} are the source's best-effort localisation. jut.su's
 * primary {@code title} field is always Russian; Kodik exposes both. Consumers must treat blanks as
 * {@code null} (see {@link Builder}). Likewise, {@link #screenshotUrls} and {@link #trailerUrls}
 * default to empty lists and the JSON serializer drops empty collections via {@link
 * JsonInclude.Include#NON_EMPTY} — null-vs-empty is not a meaningful distinction on the wire.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record SourceContentInfo(
        @Nullable String titleRu,
        @Nullable String titleEn,
        @Nullable Integer year,
        @Nonnull ContentKindHint kindHint,
        @Nonnull ExternalIds externalIds,
        @Nullable String posterUrl,
        @Nullable String bigPosterUrl,
        @Nonnull List<String> screenshotUrls,
        @Nonnull List<String> trailerUrls) {

    public SourceContentInfo {
        Objects.requireNonNull(kindHint, "kindHint");
        Objects.requireNonNull(externalIds, "externalIds");
        titleRu = blankToNull(titleRu);
        titleEn = blankToNull(titleEn);
        posterUrl = blankToNull(posterUrl);
        bigPosterUrl = blankToNull(bigPosterUrl);
        screenshotUrls = sanitiseList(screenshotUrls);
        trailerUrls = sanitiseList(trailerUrls);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static List<String> sanitiseList(@Nullable List<String> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream()
                .map(SourceContentInfo::blankToNull)
                .filter(Objects::nonNull)
                .toList();
    }

    public static final class Builder {
        @Nullable private String titleRu;
        @Nullable private String titleEn;
        @Nullable private Integer year;
        private ContentKindHint kindHint = ContentKindHint.UNKNOWN;
        private ExternalIds externalIds = ExternalIds.empty();
        @Nullable private String posterUrl;
        @Nullable private String bigPosterUrl;
        private List<String> screenshotUrls = List.of();
        private List<String> trailerUrls = List.of();

        private Builder() {}

        public Builder titleRu(@Nullable String value) {
            this.titleRu = value;
            return this;
        }

        public Builder titleEn(@Nullable String value) {
            this.titleEn = value;
            return this;
        }

        public Builder year(@Nullable Integer value) {
            this.year = value;
            return this;
        }

        public Builder kindHint(ContentKindHint value) {
            this.kindHint = Objects.requireNonNull(value, "kindHint");
            return this;
        }

        public Builder externalIds(ExternalIds value) {
            this.externalIds = Objects.requireNonNull(value, "externalIds");
            return this;
        }

        public Builder posterUrl(@Nullable String value) {
            this.posterUrl = value;
            return this;
        }

        public Builder bigPosterUrl(@Nullable String value) {
            this.bigPosterUrl = value;
            return this;
        }

        public Builder screenshotUrls(@Nullable List<String> value) {
            this.screenshotUrls = value == null ? List.of() : List.copyOf(value);
            return this;
        }

        public Builder trailerUrls(@Nullable List<String> value) {
            this.trailerUrls = value == null ? List.of() : List.copyOf(value);
            return this;
        }

        public SourceContentInfo build() {
            return new SourceContentInfo(
                    titleRu,
                    titleEn,
                    year,
                    kindHint,
                    externalIds,
                    posterUrl,
                    bigPosterUrl,
                    screenshotUrls,
                    trailerUrls);
        }
    }
}
