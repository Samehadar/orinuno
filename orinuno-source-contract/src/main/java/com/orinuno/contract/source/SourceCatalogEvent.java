package com.orinuno.contract.source;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.annotation.Nonnull;
import java.util.List;
import java.util.Objects;

/**
 * Producer-side event sealed family. Every interaction between a source bounded context (kodik /
 * jutsu / aniboom / sibnet / …) and any catalog consumer (in-process L3 sink, the external meter, future
 * OSS aggregators) crosses through one of these variants.
 *
 * <p>Variants:
 *
 * <ul>
 *   <li>{@link TitleObserved} — the source observed metadata about a title but has not (yet)
 *       decoded any episode/variant URLs. This is what {@code KodikCatalogIngestion} and {@code
 *       JutsuCatalogIngestion} emit today; the canonical row is found-or-created from the chrome
 *       alone, episodes come later via {@link EpisodesUpdated}.
 *   <li>{@link MovieDiscovered} — film-shaped content, with one playable variant. Maps to meter's
 *       {@code ExportMovieRequest}.
 *   <li>{@link SeriesDiscovered} — series-shaped content, with at least one season carrying at
 *       least one episode. Maps to meter's {@code ExportSerialRequest}.
 *   <li>{@link EpisodesUpdated} — incremental refresh: the title already exists in the consumer's
 *       catalog and the source observed new/updated episode rows. Maps to meter's {@code
 *       ExportSerialRequest} too (meter's EXTENDING strategy handles the merge).
 *   <li>{@link SourceRemoved} — the source no longer carries this title (Kodik /list dropped it,
 *       jut.su 404'd the slug). No meter equivalent today; OSS consumers may persist as a soft
 *       removal flag.
 * </ul>
 *
 * <p>Jackson polymorphism uses {@code @JsonTypeInfo(NAME)} with the discriminator property {@code
 * kind} placed at the top of the JSON object. The wire-form values are stable kebab-case tags
 * ({@code "title-observed"}, {@code "movie-discovered"}, …) — pinning the discriminator explicitly
 * is more robust than DEDUCTION because some variants are strict subsets of others (e.g. {@link
 * SourceRemoved} is {@code identifier + provenance}, which is also a valid prefix of every other
 * variant's payload), so Jackson cannot uniquely deduce them by shape alone.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(name = "title-observed", value = SourceCatalogEvent.TitleObserved.class),
    @JsonSubTypes.Type(name = "movie-discovered", value = SourceCatalogEvent.MovieDiscovered.class),
    @JsonSubTypes.Type(
            name = "series-discovered",
            value = SourceCatalogEvent.SeriesDiscovered.class),
    @JsonSubTypes.Type(name = "episodes-updated", value = SourceCatalogEvent.EpisodesUpdated.class),
    @JsonSubTypes.Type(name = "source-removed", value = SourceCatalogEvent.SourceRemoved.class)
})
public sealed interface SourceCatalogEvent {

    @Nonnull
    SourceIdentifier identifier();

    @Nonnull
    Provenance provenance();

    /**
     * Title-level observation without playable episodes. Primary use case today: the L1 → L3
     * hand-off inside {@code orinuno-app} (Kodik {@code ContentService.findOrCreateContent} and
     * jut.su's {@code JutsuCatalogSyncService}).
     */
    record TitleObserved(
            @Nonnull SourceIdentifier identifier,
            @Nonnull SourceContentInfo info,
            @Nonnull Provenance provenance)
            implements SourceCatalogEvent {

        public TitleObserved {
            Objects.requireNonNull(identifier, "identifier");
            Objects.requireNonNull(info, "info");
            Objects.requireNonNull(provenance, "provenance");
        }
    }

    /** Film-shaped event. Maps to meter's {@code ExportMovieRequest}. */
    record MovieDiscovered(
            @Nonnull SourceIdentifier identifier,
            @Nonnull SourceContentInfo info,
            @Nonnull SourceEpisodeVariant variant,
            @Nonnull Provenance provenance)
            implements SourceCatalogEvent {

        public MovieDiscovered {
            Objects.requireNonNull(identifier, "identifier");
            Objects.requireNonNull(info, "info");
            Objects.requireNonNull(variant, "variant");
            Objects.requireNonNull(provenance, "provenance");
        }
    }

    /** Series-shaped event. Maps to meter's {@code ExportSerialRequest}. */
    record SeriesDiscovered(
            @Nonnull SourceIdentifier identifier,
            @Nonnull SourceContentInfo info,
            @Nonnull List<SourceSeason> seasons,
            @Nonnull Provenance provenance)
            implements SourceCatalogEvent {

        public SeriesDiscovered {
            Objects.requireNonNull(identifier, "identifier");
            Objects.requireNonNull(info, "info");
            Objects.requireNonNull(provenance, "provenance");
            seasons = List.copyOf(Objects.requireNonNull(seasons, "seasons"));
            if (seasons.isEmpty()) {
                throw new IllegalArgumentException(
                        "SeriesDiscovered.seasons must not be empty (use TitleObserved if no"
                                + " episodes are known yet)");
            }
        }
    }

    /**
     * Incremental episode refresh on an existing title. Carries only the changed seasons —
     * consumers must merge into existing canonical state rather than treating this as the full
     * picture.
     */
    record EpisodesUpdated(
            @Nonnull SourceIdentifier identifier,
            @Nonnull List<SourceSeason> seasons,
            @Nonnull Provenance provenance)
            implements SourceCatalogEvent {

        public EpisodesUpdated {
            Objects.requireNonNull(identifier, "identifier");
            Objects.requireNonNull(provenance, "provenance");
            seasons = List.copyOf(Objects.requireNonNull(seasons, "seasons"));
            if (seasons.isEmpty()) {
                throw new IllegalArgumentException("EpisodesUpdated.seasons must not be empty");
            }
        }
    }

    /**
     * The source no longer carries this title. No meter equivalent today (parser-* services drop
     * removed rows silently); OSS L3 ingestion may persist as a soft-removal flag for visibility.
     */
    record SourceRemoved(@Nonnull SourceIdentifier identifier, @Nonnull Provenance provenance)
            implements SourceCatalogEvent {

        public SourceRemoved {
            Objects.requireNonNull(identifier, "identifier");
            Objects.requireNonNull(provenance, "provenance");
        }
    }
}
