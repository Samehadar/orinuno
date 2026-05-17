package com.orinuno.contract.source;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Objects;

/**
 * Producer-side event sealed family. Every interaction between a source bounded context (kodik /
 * jutsu / aniboom / sibnet / …) and any catalog consumer (the in-process L3 sink, the OSS meter
 * aggregator, or any out-of-tree downstream) crosses through one of these variants.
 *
 * <p>Variants:
 *
 * <ul>
 *   <li>{@link TitleObserved} — the source observed metadata about a title but has not (yet)
 *       decoded any episode/variant URLs. This is what {@code KodikCatalogIngestion} and {@code
 *       JutsuCatalogIngestion} emit today; the canonical row is found-or-created from the chrome
 *       alone, episodes come later via {@link EpisodesUpdated}.
 *   <li>{@link MovieDiscovered} — film-shaped content, with one playable variant.
 *   <li>{@link SeriesDiscovered} — series-shaped content, with at least one season carrying at
 *       least one episode.
 *   <li>{@link EpisodesUpdated} — incremental refresh: the title already exists in the consumer's
 *       catalog and the source observed new/updated episode rows. Consumers merge episodes onto the
 *       existing canonical row.
 *   <li>{@link SourceRemoved} — the source no longer carries this title (Kodik /list dropped it,
 *       jut.su 404'd the slug). Consumers may persist as a soft removal flag.
 *   <li>{@link VariantDecoded} — a previously-discovered variant has had its playable CDN URL
 *       resolved (Kodik decoder ran, Aniboom token minted, …). Emitted by the bounded context that
 *       owns the decoder (today: {@code orinuno-app}'s {@code ParserService} for Kodik) so the
 *       canonical L2 layer can record the decoded URL without polling the source's L1 schema.
 *       Recorded as part of ADR 0021 §B2-decoded — the channel that lets {@code
 *       KodikEpisodeDualWriteService} retire (ADR 0021 B1).
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
    @JsonSubTypes.Type(name = "source-removed", value = SourceCatalogEvent.SourceRemoved.class),
    @JsonSubTypes.Type(name = "variant-decoded", value = SourceCatalogEvent.VariantDecoded.class)
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

    /**
     * Post-decode hand-off. The variant identified by {@code variantIdentifier} (matching what a
     * prior {@link MovieDiscovered} / {@link SeriesDiscovered} / {@link EpisodesUpdated} event
     * already carried) has been resolved to a playable CDN URL.
     *
     * <p>Carries the canonical {@code (season, episode)} tuple alongside the title-level {@link
     * #identifier()} so the consumer can locate the matching {@code episode_source} row by its
     * unique key {@code (content_id, season, episode, translator_id, provider)} — see {@code
     * CatalogSinkEventEmitter}. Films ride as {@code season=0, episode=1} per the {@code
     * SourceSeason} javadoc convention.
     *
     * <p>{@code decodeMethod} mirrors the DECODE-8 discriminator on {@code episode_video} ({@code
     * REGEX} / {@code SNIFF} / {@code PROVIDER_API} / …); {@code ttlSeconds} is provider-specific
     * (Aniboom CDN tokens expire after ~6h, Sibnet direct URLs are stable so the field stays {@code
     * null}). Both are optional and forwarded verbatim to {@code episode_video}.
     */
    record VariantDecoded(
            @Nonnull SourceIdentifier identifier,
            int season,
            int episode,
            @Nonnull SourceIdentifier variantIdentifier,
            @Nonnull String decodedMediaUrl,
            @Nonnull String decodedQuality,
            @Nullable String decodeMethod,
            @Nullable Integer ttlSeconds,
            @Nonnull Provenance provenance)
            implements SourceCatalogEvent {

        public VariantDecoded {
            Objects.requireNonNull(identifier, "identifier");
            Objects.requireNonNull(variantIdentifier, "variantIdentifier");
            Objects.requireNonNull(decodedMediaUrl, "decodedMediaUrl");
            Objects.requireNonNull(decodedQuality, "decodedQuality");
            Objects.requireNonNull(provenance, "provenance");
            if (decodedMediaUrl.isBlank()) {
                throw new IllegalArgumentException("decodedMediaUrl must not be blank");
            }
            if (decodedQuality.isBlank()) {
                throw new IllegalArgumentException("decodedQuality must not be blank");
            }
            if (season < 0) {
                throw new IllegalArgumentException("season must be >= 0 (films use season=0)");
            }
            if (episode < 1) {
                throw new IllegalArgumentException("episode must be >= 1");
            }
        }
    }
}
