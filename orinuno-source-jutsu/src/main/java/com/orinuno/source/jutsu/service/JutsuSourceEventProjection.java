/*
 * JutsuSourceEventProjection — ADR 0019 Phase 4.6.
 *
 * Reads jutsu_title + jutsu_episode + jutsu_film rows from this service's
 * MySQL schema and renders them as producer-side SourceCatalogEvent payloads.
 * Mirrors KodikSourceEventProjection (orinuno-source-kodik Phase 2.6).
 *
 * No L2/L3 enrichment here — episode_video / catalog_* tables live in the
 * meter shared schema (ADR 0018 Phase 5), not this service. Consumers (meter's
 * JutsuRemoteEventPoller in Phase 4.11) receive the relative episode URL on
 * SourceEpisodeVariant#mediaUrl and decode JIT just before they need the
 * bytes (TECH_DEBT ARCH-0018, JIT-decode trajectory).
 */
package com.orinuno.source.jutsu.service;

import com.orinuno.contract.source.SourceCatalogEvent;
import com.orinuno.source.jutsu.JutsuSourceProperties;
import com.orinuno.source.jutsu.mapper.JutsuSourceEventMapper;
import com.orinuno.source.jutsu.model.JutsuEpisode;
import com.orinuno.source.jutsu.model.JutsuFilm;
import com.orinuno.source.jutsu.model.JutsuTitle;
import com.orinuno.source.jutsu.repository.JutsuEpisodeRepository;
import com.orinuno.source.jutsu.repository.JutsuFilmRepository;
import com.orinuno.source.jutsu.repository.JutsuTitleRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class JutsuSourceEventProjection {

    private final JutsuTitleRepository titleRepository;
    private final JutsuEpisodeRepository episodeRepository;
    private final JutsuFilmRepository filmRepository;
    private final Clock clock;
    private final JutsuSourceProperties props;

    /**
     * Page through jut.su titles oldest-first by {@code last_seen_at}, hydrating each one with its
     * episodes + films and rendering as a SourceCatalogEvent. {@code updatedSince} is the
     * incremental polling watermark — consumers pass the {@code Provenance.fetchedAt} of the latest
     * event they processed and re-poll only for newer rows.
     */
    public List<SourceCatalogEvent> findReadyEvents(LocalDateTime updatedSince, int limit) {
        List<JutsuTitle> readyTitles = titleRepository.findReadyForExport(limit, updatedSince);
        log.debug(
                "JutsuSourceEventProjection: rendering {} title(s) (updatedSince={})",
                readyTitles.size(),
                updatedSince);
        return readyTitles.stream()
                .map(
                        title -> {
                            List<JutsuEpisode> episodes =
                                    episodeRepository.findBySlug(title.getSlug());
                            List<JutsuFilm> films = filmRepository.findBySlug(title.getSlug());
                            return JutsuSourceEventMapper.toEvent(
                                    title, episodes, films, clock, props.getBaseUrl());
                        })
                .toList();
    }
}
