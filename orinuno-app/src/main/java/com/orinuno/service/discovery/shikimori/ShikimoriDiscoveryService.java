package com.orinuno.service.discovery.shikimori;

import com.orinuno.model.dto.ParseRequestDto;
import com.orinuno.service.ParserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * PLAYER-5 (ADR 0007) — discovery primitive: given a Shikimori anime id, ingest the matching Kodik
 * catalogue rows by handing the id off to {@link ParserService}. Shikimori is a metadata index, not
 * a video source — see ADR 0007 for the rationale.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShikimoriDiscoveryService {

    private final ShikimoriClient shikimoriClient;
    private final ParserService parserService;

    public Mono<DiscoveryResult> discover(long shikimoriId) {
        return shikimoriClient
                .fetchAnime(shikimoriId)
                .flatMap(
                        anime -> {
                            String title =
                                    anime == null || anime.path("name").isMissingNode()
                                            ? null
                                            : anime.path("name").asText(null);
                            String russianTitle =
                                    anime == null || anime.path("russian").isMissingNode()
                                            ? null
                                            : anime.path("russian").asText(null);
                            String resolvedTitle =
                                    russianTitle != null && !russianTitle.isBlank()
                                            ? russianTitle
                                            : title;
                            log.info(
                                    "PLAYER-5 discover: shikimori_id={} resolved title=\"{}\"",
                                    shikimoriId,
                                    resolvedTitle);
                            ParseRequestDto request = new ParseRequestDto();
                            request.setShikimoriId(String.valueOf(shikimoriId));
                            request.setTitle(resolvedTitle);
                            request.setDecodeLinks(false);
                            return parserService
                                    .search(request)
                                    .map(
                                            list ->
                                                    new DiscoveryResult(
                                                            shikimoriId,
                                                            resolvedTitle,
                                                            list == null ? 0 : list.size()));
                        })
                .onErrorResume(
                        ex -> {
                            log.warn(
                                    "PLAYER-5 discover failed for shikimori_id={}: {}",
                                    shikimoriId,
                                    ex.toString());
                            return Mono.just(new DiscoveryResult(shikimoriId, null, 0));
                        });
    }

    public record DiscoveryResult(long shikimoriId, String resolvedTitle, int ingestedContents) {}
}
