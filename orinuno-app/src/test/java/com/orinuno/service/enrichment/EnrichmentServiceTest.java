package com.orinuno.service.enrichment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orinuno.model.KodikContent;
import com.orinuno.model.KodikContentEnrichment;
import com.orinuno.repository.ContentRepository;
import com.orinuno.repository.KodikContentEnrichmentRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class EnrichmentServiceTest {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = (ObjectProvider<T>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static EnrichmentClient client(String source, KodikContentEnrichment row) {
        return new EnrichmentClient() {
            @Override
            public String source() {
                return source;
            }

            @Override
            public Mono<KodikContentEnrichment> fetch(KodikContent content) {
                return row == null ? Mono.empty() : Mono.just(row);
            }
        };
    }

    private static EnrichmentClient failingClient(String source, RuntimeException ex) {
        return new EnrichmentClient() {
            @Override
            public String source() {
                return source;
            }

            @Override
            public Mono<KodikContentEnrichment> fetch(KodikContent content) {
                return Mono.error(ex);
            }
        };
    }

    private static KodikContentEnrichment row(Long contentId, String source) {
        return KodikContentEnrichment.builder().contentId(contentId).source(source).build();
    }

    @Test
    void writesOneRowPerSucceedingClient() {
        KodikContentEnrichmentRepository repo = mock(KodikContentEnrichmentRepository.class);
        EnrichmentService service =
                new EnrichmentService(
                        List.of(
                                client("SHIKIMORI", row(1L, "SHIKIMORI")),
                                client("KINOPOISK", row(1L, "KINOPOISK"))),
                        provider(null),
                        provider(repo));

        KodikContent content = new KodikContent();
        content.setId(1L);

        StepVerifier.create(service.enrich(content))
                .assertNext(
                        s -> {
                            assertThat(s.contentId()).isEqualTo(1L);
                            assertThat(s.attempted()).isEqualTo(2);
                            assertThat(s.written()).isEqualTo(2);
                        })
                .verifyComplete();

        verify(repo, times(2)).upsert(any());
    }

    @Test
    void emptyClientResultIsCountedAsAttemptedButNotWritten() {
        KodikContentEnrichmentRepository repo = mock(KodikContentEnrichmentRepository.class);
        EnrichmentService service =
                new EnrichmentService(
                        List.of(client("SHIKIMORI", null)), provider(null), provider(repo));

        KodikContent content = new KodikContent();
        content.setId(1L);

        StepVerifier.create(service.enrich(content))
                .assertNext(
                        s -> {
                            assertThat(s.attempted()).isEqualTo(1);
                            assertThat(s.written()).isZero();
                        })
                .verifyComplete();

        verify(repo, never()).upsert(any());
    }

    @Test
    void clientFailureDoesNotAbortOtherClients() {
        KodikContentEnrichmentRepository repo = mock(KodikContentEnrichmentRepository.class);
        EnrichmentService service =
                new EnrichmentService(
                        List.of(
                                failingClient("SHIKIMORI", new RuntimeException("upstream 500")),
                                client("KINOPOISK", row(2L, "KINOPOISK"))),
                        provider(null),
                        provider(repo));

        KodikContent content = new KodikContent();
        content.setId(2L);

        StepVerifier.create(service.enrich(content))
                .assertNext(
                        s -> {
                            assertThat(s.attempted()).isEqualTo(2);
                            assertThat(s.written()).isEqualTo(1);
                        })
                .verifyComplete();
    }

    @Test
    void repoUpsertFailureIsAbsorbed() {
        KodikContentEnrichmentRepository repo = mock(KodikContentEnrichmentRepository.class);
        doThrow(new RuntimeException("db down")).when(repo).upsert(any());
        EnrichmentService service =
                new EnrichmentService(
                        List.of(client("SHIKIMORI", row(3L, "SHIKIMORI"))),
                        provider(null),
                        provider(repo));

        KodikContent content = new KodikContent();
        content.setId(3L);

        StepVerifier.create(service.enrich(content))
                .assertNext(s -> assertThat(s.written()).isZero())
                .verifyComplete();
    }

    @Test
    void noClientsReturnsZeroSummary() {
        EnrichmentService service =
                new EnrichmentService(List.of(), provider(null), provider(null));
        KodikContent content = new KodikContent();
        content.setId(4L);
        StepVerifier.create(service.enrich(content))
                .assertNext(
                        s -> {
                            assertThat(s.attempted()).isZero();
                            assertThat(s.written()).isZero();
                        })
                .verifyComplete();
    }

    @Test
    void enrichByContentIdLooksUpInContentRepo() {
        ContentRepository contentRepo = mock(ContentRepository.class);
        KodikContent content = new KodikContent();
        content.setId(7L);
        when(contentRepo.findById(7L)).thenReturn(Optional.of(content));
        EnrichmentService service =
                new EnrichmentService(
                        List.of(client("SHIKIMORI", row(7L, "SHIKIMORI"))),
                        provider(contentRepo),
                        provider(null));
        StepVerifier.create(service.enrichByContentId(7L))
                .assertNext(s -> assertThat(s.attempted()).isEqualTo(1))
                .verifyComplete();
    }

    @Test
    void enrichByContentIdReturnsZeroWhenContentMissing() {
        ContentRepository contentRepo = mock(ContentRepository.class);
        when(contentRepo.findById(99L)).thenReturn(Optional.empty());
        EnrichmentService service =
                new EnrichmentService(List.of(), provider(contentRepo), provider(null));
        StepVerifier.create(service.enrichByContentId(99L))
                .assertNext(
                        s -> {
                            assertThat(s.attempted()).isZero();
                            assertThat(s.written()).isZero();
                        })
                .verifyComplete();
    }

    @Test
    void fetchedAtAndLastRefreshedAtAreFilledIfMissing() {
        KodikContentEnrichmentRepository repo = mock(KodikContentEnrichmentRepository.class);
        KodikContentEnrichment row =
                KodikContentEnrichment.builder().contentId(8L).source("SHIKIMORI").build();
        EnrichmentService service =
                new EnrichmentService(
                        List.of(client("SHIKIMORI", row)), provider(null), provider(repo));
        KodikContent content = new KodikContent();
        content.setId(8L);
        StepVerifier.create(service.enrich(content)).expectNextCount(1).verifyComplete();
        assertThat(row.getFetchedAt()).isNotNull();
        assertThat(row.getLastRefreshedAt()).isNotNull();
        assertThat(row.getFetchedAt()).isBefore(LocalDateTime.now().plusSeconds(1));
    }
}
