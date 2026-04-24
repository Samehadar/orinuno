package com.orinuno.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.orinuno.client.KodikApiClient;
import com.orinuno.client.dto.KodikSearchResponse;
import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.model.KodikContent;
import com.orinuno.model.dto.ParseRequestDto;
import com.orinuno.repository.EpisodeVariantRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class ParserServiceTest {

    @Mock private KodikApiClient kodikApiClient;
    @Mock private ContentService contentService;
    @Mock private KodikVideoDecoderService decoderService;
    @Mock private EpisodeVariantRepository episodeVariantRepository;

    private ParserService parserService;

    @BeforeEach
    void setUp() {
        OrinunoProperties properties = new OrinunoProperties();
        properties.getKodik().setRequestDelayMs(0);
        parserService =
                new ParserService(
                        kodikApiClient,
                        contentService,
                        decoderService,
                        episodeVariantRepository,
                        properties);
    }

    @Test
    @DisplayName("Should process search results and save content")
    void shouldProcessSearchResults() {
        KodikSearchResponse.Result result =
                KodikSearchResponse.Result.builder()
                        .id("movie-1")
                        .type("foreign-movie")
                        .title("Test")
                        .kinopoiskId("123")
                        .translation(
                                KodikSearchResponse.Translation.builder()
                                        .id(1)
                                        .title("Дубляж")
                                        .type("voice")
                                        .build())
                        .build();

        KodikSearchResponse response =
                KodikSearchResponse.builder().total(1).results(List.of(result)).build();

        KodikContent savedContent = KodikContent.builder().id(1L).title("Test").build();

        when(kodikApiClient.search(any())).thenReturn(Mono.just(response));
        when(contentService.findOrCreateContent(any())).thenReturn(savedContent);
        doNothing().when(contentService).saveVariants(any());

        ParseRequestDto request =
                ParseRequestDto.builder().kinopoiskId("123").decodeLinks(false).build();

        StepVerifier.create(parserService.search(request))
                .assertNext(
                        contents -> {
                            assertThat(contents).hasSize(1);
                            assertThat(contents.get(0).getTitle()).isEqualTo("Test");
                        })
                .verifyComplete();

        verify(contentService).findOrCreateContent(any());
        verify(contentService).saveVariants(any());
    }

    @Test
    @DisplayName("Should handle empty search results")
    void shouldHandleEmptyResults() {
        KodikSearchResponse response =
                KodikSearchResponse.builder().total(0).results(List.of()).build();

        when(kodikApiClient.search(any())).thenReturn(Mono.just(response));

        ParseRequestDto request = ParseRequestDto.builder().title("Nonexistent").build();

        StepVerifier.create(parserService.search(request))
                .assertNext(contents -> assertThat(contents).isEmpty())
                .verifyComplete();

        verify(contentService, never()).findOrCreateContent(any());
    }
}
