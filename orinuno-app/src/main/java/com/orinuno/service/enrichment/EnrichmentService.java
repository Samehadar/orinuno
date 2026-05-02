package com.orinuno.service.enrichment;

import com.orinuno.model.KodikContent;
import com.orinuno.repository.ContentRepository;
import com.orinuno.repository.KodikContentEnrichmentRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * META-1 (ADR 0010) — orchestrator that runs every {@link EnrichmentClient} for a given content row
 * and persists the resulting rows. Failures from a single client never abort the others.
 *
 * <p>Repositories are wired via {@link ObjectProvider} so unit tests can pass {@code null} and the
 * service degrades to a no-op write path.
 */
@Slf4j
@Service
public class EnrichmentService {

    private final List<EnrichmentClient> clients;
    private final ObjectProvider<ContentRepository> contentRepoProvider;
    private final ObjectProvider<KodikContentEnrichmentRepository> enrichmentRepoProvider;

    public EnrichmentService(
            List<EnrichmentClient> clients,
            ObjectProvider<ContentRepository> contentRepoProvider,
            ObjectProvider<KodikContentEnrichmentRepository> enrichmentRepoProvider) {
        this.clients = clients == null ? List.of() : clients;
        this.contentRepoProvider = contentRepoProvider;
        this.enrichmentRepoProvider = enrichmentRepoProvider;
    }

    public Mono<EnrichmentSummary> enrichByContentId(Long contentId) {
        ContentRepository contentRepo = contentRepoProvider.getIfAvailable();
        if (contentRepo == null) {
            return Mono.just(new EnrichmentSummary(contentId, 0, 0));
        }
        Optional<KodikContent> content = contentRepo.findById(contentId);
        if (content.isEmpty()) {
            return Mono.just(new EnrichmentSummary(contentId, 0, 0));
        }
        return enrich(content.get());
    }

    public Mono<EnrichmentSummary> enrich(KodikContent content) {
        if (content == null || clients.isEmpty()) {
            return Mono.just(new EnrichmentSummary(content == null ? null : content.getId(), 0, 0));
        }
        KodikContentEnrichmentRepository repo = enrichmentRepoProvider.getIfAvailable();
        return Flux.fromIterable(clients)
                .flatMap(client -> runOne(client, content, repo))
                .collectList()
                .map(
                        results -> {
                            int attempted = results.size();
                            int written =
                                    (int) results.stream().filter(Boolean::booleanValue).count();
                            return new EnrichmentSummary(content.getId(), attempted, written);
                        });
    }

    private Mono<Boolean> runOne(
            EnrichmentClient client, KodikContent content, KodikContentEnrichmentRepository repo) {
        return client.fetch(content)
                .map(
                        row -> {
                            if (row == null) {
                                return false;
                            }
                            if (repo == null) {
                                log.debug(
                                        "META-1 enrichment repo unavailable; would have upserted"
                                                + " content_id={} source={}",
                                        content.getId(),
                                        client.source());
                                return false;
                            }
                            try {
                                if (row.getFetchedAt() == null) {
                                    row.setFetchedAt(LocalDateTime.now());
                                }
                                if (row.getLastRefreshedAt() == null) {
                                    row.setLastRefreshedAt(LocalDateTime.now());
                                }
                                repo.upsert(row);
                                return true;
                            } catch (Exception ex) {
                                log.warn(
                                        "META-1 upsert failed for content_id={} source={}: {}",
                                        content.getId(),
                                        client.source(),
                                        ex.toString());
                                return false;
                            }
                        })
                .defaultIfEmpty(false)
                .onErrorResume(
                        ex -> {
                            log.warn(
                                    "META-1 client {} failed for content_id={}: {}",
                                    client.source(),
                                    content.getId(),
                                    ex.toString());
                            return Mono.just(false);
                        });
    }

    public record EnrichmentSummary(Long contentId, int attempted, int written) {}
}
