package com.orinuno.contract.source;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.annotation.Nullable;
import java.util.Objects;

/**
 * Third-party external-database identifiers harvested from a source's metadata. Every field is a
 * plain {@code @Nullable String} on purpose — this artifact does not depend on any project's
 * internal value-object wrappers, and consumers (Kin's meter, future OSS aggregators) re-wrap as
 * needed.
 *
 * <p>Coverage matches the union of:
 *
 * <ul>
 *   <li>meter's {@code ContentCommonInfo} ids: {@code kinopoiskId}, {@code imdbId}, {@code
 *       shikimoriId}, {@code myDramaListId} (renamed {@code mdlId}), {@code tmdbId}.
 *   <li>fields that AnimeParsers / kodik-api-rust / kodikwrapper expose but meter doesn't track
 *       today: {@code malId}, {@code anidbId}, {@code anilistId}, {@code worldartAnimationId},
 *       {@code worldartCinemaId}.
 * </ul>
 *
 * <p>Whitespace-only strings are normalised to {@code null} in the constructor so consumers don't
 * have to defend against lookups like {@code SHIKIMORI -> ""}. Use {@link Builder} for ergonomics.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExternalIds(
        @Nullable String kinopoiskId,
        @Nullable String imdbId,
        @Nullable String shikimoriId,
        @Nullable String malId,
        @Nullable String anidbId,
        @Nullable String anilistId,
        @Nullable String tmdbId,
        @Nullable String mdlId,
        @Nullable String worldartAnimationId,
        @Nullable String worldartCinemaId) {

    public ExternalIds {
        kinopoiskId = blankToNull(kinopoiskId);
        imdbId = blankToNull(imdbId);
        shikimoriId = blankToNull(shikimoriId);
        malId = blankToNull(malId);
        anidbId = blankToNull(anidbId);
        anilistId = blankToNull(anilistId);
        tmdbId = blankToNull(tmdbId);
        mdlId = blankToNull(mdlId);
        worldartAnimationId = blankToNull(worldartAnimationId);
        worldartCinemaId = blankToNull(worldartCinemaId);
    }

    /** Empty instance — convenient default for sources that don't expose any external ids yet. */
    public static ExternalIds empty() {
        return new ExternalIds(null, null, null, null, null, null, null, null, null, null);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return kinopoiskId == null
                && imdbId == null
                && shikimoriId == null
                && malId == null
                && anidbId == null
                && anilistId == null
                && tmdbId == null
                && mdlId == null
                && worldartAnimationId == null
                && worldartCinemaId == null;
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

    public static final class Builder {
        @Nullable private String kinopoiskId;
        @Nullable private String imdbId;
        @Nullable private String shikimoriId;
        @Nullable private String malId;
        @Nullable private String anidbId;
        @Nullable private String anilistId;
        @Nullable private String tmdbId;
        @Nullable private String mdlId;
        @Nullable private String worldartAnimationId;
        @Nullable private String worldartCinemaId;

        private Builder() {}

        public Builder kinopoiskId(@Nullable String value) {
            this.kinopoiskId = value;
            return this;
        }

        public Builder imdbId(@Nullable String value) {
            this.imdbId = value;
            return this;
        }

        public Builder shikimoriId(@Nullable String value) {
            this.shikimoriId = value;
            return this;
        }

        public Builder malId(@Nullable String value) {
            this.malId = value;
            return this;
        }

        public Builder anidbId(@Nullable String value) {
            this.anidbId = value;
            return this;
        }

        public Builder anilistId(@Nullable String value) {
            this.anilistId = value;
            return this;
        }

        public Builder tmdbId(@Nullable String value) {
            this.tmdbId = value;
            return this;
        }

        public Builder mdlId(@Nullable String value) {
            this.mdlId = value;
            return this;
        }

        public Builder worldartAnimationId(@Nullable String value) {
            this.worldartAnimationId = value;
            return this;
        }

        public Builder worldartCinemaId(@Nullable String value) {
            this.worldartCinemaId = value;
            return this;
        }

        public ExternalIds build() {
            return new ExternalIds(
                    kinopoiskId,
                    imdbId,
                    shikimoriId,
                    malId,
                    anidbId,
                    anilistId,
                    tmdbId,
                    mdlId,
                    worldartAnimationId,
                    worldartCinemaId);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ExternalIds other)) return false;
        return Objects.equals(kinopoiskId, other.kinopoiskId)
                && Objects.equals(imdbId, other.imdbId)
                && Objects.equals(shikimoriId, other.shikimoriId)
                && Objects.equals(malId, other.malId)
                && Objects.equals(anidbId, other.anidbId)
                && Objects.equals(anilistId, other.anilistId)
                && Objects.equals(tmdbId, other.tmdbId)
                && Objects.equals(mdlId, other.mdlId)
                && Objects.equals(worldartAnimationId, other.worldartAnimationId)
                && Objects.equals(worldartCinemaId, other.worldartCinemaId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                kinopoiskId,
                imdbId,
                shikimoriId,
                malId,
                anidbId,
                anilistId,
                tmdbId,
                mdlId,
                worldartAnimationId,
                worldartCinemaId);
    }
}
