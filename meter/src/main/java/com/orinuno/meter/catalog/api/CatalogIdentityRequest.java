package com.orinuno.meter.catalog.api;

import com.orinuno.meter.catalog.model.CatalogContentKind;
import com.orinuno.meter.catalog.model.CatalogSourceType;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Inbound request payload for {@link CatalogPublicApi#findOrCreateContent(CatalogIdentityRequest)}
 * (ARCH-0016 P1b Step 1.B). One instance describes "the source identity tuple a sync worker just
 * observed, plus whatever external-database ids it was able to harvest".
 *
 * <p>{@code sourceType}/{@code sourceId} together identify the per-source row that triggered the
 * call (e.g. {@code KODIK} + a Kodik raw id, or {@code JUTSU} + a slug). They are the resolver's
 * fallback anchor when none of the external-database ids match.
 *
 * <p>{@code externalIds} carries third-party identifiers harvested from the source's metadata —
 * Shikimori, MAL, IMDB, Kinopoisk, MDL, TMDB. The resolver uses them to merge entries observed by
 * different sources into one canonical row. Keys must be {@link
 * CatalogSourceType#isExternalDatabase()} — passing {@link CatalogSourceType#KODIK} or {@link
 * CatalogSourceType#JUTSU} here is a programming error; those go in the {@code sourceType}/{@code
 * sourceId} pair.
 *
 * <p>Display chrome ({@code titleRu}, {@code titleEn}, {@code kind}, {@code year}) is best-effort —
 * the resolver writes them only when the canonical row's corresponding column is currently null
 * (COALESCE-protected at the SQL layer). The first source to provide a chrome value wins;
 * subsequent calls don't blank it.
 */
public record CatalogIdentityRequest(
        CatalogSourceType sourceType,
        String sourceId,
        Map<CatalogSourceType, String> externalIds,
        String titleRu,
        String titleEn,
        CatalogContentKind kind,
        Integer year) {

    public CatalogIdentityRequest {
        Objects.requireNonNull(sourceType, "sourceType");
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        Map<CatalogSourceType, String> normalised = new HashMap<>();
        if (externalIds != null) {
            for (Map.Entry<CatalogSourceType, String> e : externalIds.entrySet()) {
                if (e.getKey() == null || e.getValue() == null || e.getValue().isBlank()) {
                    continue;
                }
                if (!e.getKey().isExternalDatabase()) {
                    throw new IllegalArgumentException(
                            "externalIds keys must be external databases (Shikimori/MAL/IMDB/"
                                    + "Kinopoisk/MDL/TMDB); got "
                                    + e.getKey());
                }
                normalised.put(e.getKey(), e.getValue().trim());
            }
        }
        externalIds = Map.copyOf(normalised);
    }

    public Optional<String> externalId(CatalogSourceType type) {
        return Optional.ofNullable(externalIds.get(type));
    }

    public static Builder builder(CatalogSourceType sourceType, String sourceId) {
        return new Builder(sourceType, sourceId);
    }

    public static final class Builder {
        private final CatalogSourceType sourceType;
        private final String sourceId;
        private final Map<CatalogSourceType, String> externalIds = new HashMap<>();
        private String titleRu;
        private String titleEn;
        private CatalogContentKind kind;
        private Integer year;

        private Builder(CatalogSourceType sourceType, String sourceId) {
            this.sourceType = sourceType;
            this.sourceId = sourceId;
        }

        public Builder externalId(CatalogSourceType type, String id) {
            if (id != null && !id.isBlank()) {
                externalIds.put(type, id);
            }
            return this;
        }

        public Builder shikimoriId(String id) {
            return externalId(CatalogSourceType.SHIKIMORI, id);
        }

        public Builder malId(String id) {
            return externalId(CatalogSourceType.MAL, id);
        }

        public Builder imdbId(String id) {
            return externalId(CatalogSourceType.IMDB, id);
        }

        public Builder kinopoiskId(String id) {
            return externalId(CatalogSourceType.KINOPOISK, id);
        }

        public Builder mdlId(String id) {
            return externalId(CatalogSourceType.MDL, id);
        }

        public Builder tmdbId(String id) {
            return externalId(CatalogSourceType.TMDB, id);
        }

        public Builder titleRu(String value) {
            this.titleRu = value;
            return this;
        }

        public Builder titleEn(String value) {
            this.titleEn = value;
            return this;
        }

        public Builder kind(CatalogContentKind value) {
            this.kind = value;
            return this;
        }

        public Builder year(Integer value) {
            this.year = value;
            return this;
        }

        public CatalogIdentityRequest build() {
            return new CatalogIdentityRequest(
                    sourceType, sourceId, externalIds, titleRu, titleEn, kind, year);
        }
    }
}
