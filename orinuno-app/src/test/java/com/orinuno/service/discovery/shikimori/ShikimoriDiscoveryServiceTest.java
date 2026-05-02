package com.orinuno.service.discovery.shikimori;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orinuno.model.dto.ParseRequestDto;
import com.orinuno.service.ParserService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ShikimoriDiscoveryServiceTest {

    private ShikimoriClient client;
    private ParserService parserService;
    private ShikimoriDiscoveryService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        client = mock(ShikimoriClient.class);
        parserService = mock(ParserService.class);
        service = new ShikimoriDiscoveryService(client, parserService);
    }

    @Test
    void prefersRussianTitleWhenPresent() throws Exception {
        JsonNode anime =
                objectMapper.readTree("{\"id\":1,\"name\":\"Naruto\",\"russian\":\"Наруто\"}");
        when(client.fetchAnime(1L)).thenReturn(Mono.just(anime));
        when(parserService.search(any())).thenReturn(Mono.just(List.of()));

        StepVerifier.create(service.discover(1L))
                .assertNext(
                        r -> {
                            assertThat(r.shikimoriId()).isEqualTo(1L);
                            assertThat(r.resolvedTitle()).isEqualTo("Наруто");
                        })
                .verifyComplete();

        ArgumentCaptor<ParseRequestDto> req = ArgumentCaptor.forClass(ParseRequestDto.class);
        verify(parserService).search(req.capture());
        assertThat(req.getValue().getShikimoriId()).isEqualTo("1");
        assertThat(req.getValue().getTitle()).isEqualTo("Наруто");
        assertThat(req.getValue().isDecodeLinks()).isFalse();
    }

    @Test
    void fallsBackToOriginalTitleWhenRussianMissing() throws Exception {
        JsonNode anime = objectMapper.readTree("{\"id\":2,\"name\":\"Bleach\"}");
        when(client.fetchAnime(2L)).thenReturn(Mono.just(anime));
        when(parserService.search(any())).thenReturn(Mono.just(List.of()));

        StepVerifier.create(service.discover(2L))
                .assertNext(r -> assertThat(r.resolvedTitle()).isEqualTo("Bleach"))
                .verifyComplete();
    }

    @Test
    void absorbsClientErrorsByEmittingZeroIngested() {
        when(client.fetchAnime(99L)).thenReturn(Mono.error(new RuntimeException("404")));

        StepVerifier.create(service.discover(99L))
                .assertNext(
                        r -> {
                            assertThat(r.ingestedContents()).isZero();
                            assertThat(r.resolvedTitle()).isNull();
                        })
                .verifyComplete();

        verify(parserService, never()).search(any());
    }

    @Test
    void countsIngestedFromParserResult() throws Exception {
        JsonNode anime = objectMapper.readTree("{\"id\":3,\"name\":\"X\"}");
        when(client.fetchAnime(3L)).thenReturn(Mono.just(anime));
        when(parserService.search(any()))
                .thenReturn(Mono.just(List.of(new com.orinuno.model.KodikContent())));

        StepVerifier.create(service.discover(3L))
                .assertNext(r -> assertThat(r.ingestedContents()).isEqualTo(1))
                .verifyComplete();
    }
}
