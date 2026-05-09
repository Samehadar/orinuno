/**
 * Producer-side event contract for orinuno content sources (ADR 0017). Pure DTOs, no Spring, no
 * consumer-specific types. This package is the only thing crossing the source-context → consumer
 * boundary inside {@code orinuno-app}, and the only artifact that downstream consumers (the external meter
 * via {@code external bridge}, future OSS aggregators) need to depend on.
 *
 * <p>Top-level entry point: {@link com.orinuno.contract.source.SourceCatalogEvent} (sealed) and
 * {@link com.orinuno.contract.source.SourceEventEmitter} (functional interface). Supporting
 * records: {@link com.orinuno.contract.source.SourceIdentifier}, {@link
 * com.orinuno.contract.source.ExternalIds}, {@link com.orinuno.contract.source.Provenance}, {@link
 * com.orinuno.contract.source.ContentKindHint}, {@link
 * com.orinuno.contract.source.SourceContentInfo}, {@link com.orinuno.contract.source.SourceSeason},
 * {@link com.orinuno.contract.source.SourceEpisode}, {@link
 * com.orinuno.contract.source.SourceEpisodeVariant}.
 *
 * <p>For the meter-shape audit and the rationale for keeping the artifact Spring-free / consumer-neutral /
 * publishable to Maven Central, see {@code docs/adr/0017-source-event-contract.md}.
 */
package com.orinuno.contract.source;
