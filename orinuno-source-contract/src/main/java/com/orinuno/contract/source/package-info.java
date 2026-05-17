/**
 * Producer-side event contract for orinuno content sources (ADR 0017). Pure DTOs, no Spring, no
 * consumer-specific types. This package is the only thing crossing the source-context → consumer
 * boundary, and the only artifact that downstream consumers (the OSS meter aggregator, any
 * out-of-tree adapter) need to depend on.
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
 * <p>For the rationale (keep the artifact Spring-free and consumer-neutral so it can ship to Maven
 * Central), see {@code docs/adr/0017-source-event-contract.md}.
 */
package com.orinuno.contract.source;
