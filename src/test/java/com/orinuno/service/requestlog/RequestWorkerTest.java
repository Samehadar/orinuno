package com.orinuno.service.requestlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.model.KodikContent;
import com.orinuno.model.OrinunoParseRequest;
import com.orinuno.model.dto.ParseRequestDto;
import com.orinuno.repository.ParseRequestRepository;
import com.orinuno.service.ParserService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

class RequestWorkerTest {

    private ParseRequestQueueService queueService;
    private ParseRequestRepository repository;
    private ParserService parserService;
    private RequestWorker worker;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock fixedClock =
            Clock.fixed(Instant.parse("2026-04-26T00:00:00Z"), ZoneId.of("UTC"));

    @BeforeEach
    void setUp() {
        queueService = mock(ParseRequestQueueService.class);
        repository = mock(ParseRequestRepository.class);
        parserService = mock(ParserService.class);
        OrinunoProperties props = new OrinunoProperties();
        worker =
                new RequestWorker(
                        queueService, repository, parserService, mapper, props, fixedClock);
    }

    @Test
    @DisplayName("tick: marks request DONE when search completes")
    void tickMarksDone() throws Exception {
        long id = 42L;
        when(queueService.claimNext()).thenReturn(Optional.of(id));
        OrinunoParseRequest claimed = baseRequest(id, "{\"title\":\"naruto\"}");
        when(repository.findById(id)).thenReturn(Optional.of(claimed));
        KodikContent c1 = new KodikContent();
        c1.setId(7L);
        when(parserService.searchInternal(any(ParseRequestDto.class), any(ProgressReporter.class)))
                .thenReturn(Mono.just(List.of(c1)));

        worker.tick();

        ArgumentCaptor<String> resultJson = ArgumentCaptor.forClass(String.class);
        verify(repository).markDone(eq(id), resultJson.capture(), any());
        assertThat(resultJson.getValue()).isEqualTo("[7]");
        verify(repository, never()).markFailed(anyLong(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("tick: marks request FAILED on exception")
    void tickMarksFailed() {
        long id = 17L;
        when(queueService.claimNext()).thenReturn(Optional.of(id));
        when(repository.findById(id))
                .thenReturn(Optional.of(baseRequest(id, "{\"title\":\"naruto\"}")));
        when(parserService.searchInternal(any(ParseRequestDto.class), any(ProgressReporter.class)))
                .thenReturn(Mono.error(new RuntimeException("boom")));

        worker.tick();

        ArgumentCaptor<String> err = ArgumentCaptor.forClass(String.class);
        verify(repository).markFailed(eq(id), err.capture(), any(), eq(0));
        assertThat(err.getValue()).contains("boom");
        verify(repository, never()).markDone(anyLong(), any(), any());
    }

    @Test
    @DisplayName("tick: marks FAILED when request_json cannot be deserialized")
    void tickHandlesBadJson() {
        long id = 9L;
        when(queueService.claimNext()).thenReturn(Optional.of(id));
        when(repository.findById(id)).thenReturn(Optional.of(baseRequest(id, "not-json")));

        worker.tick();

        verify(repository).markFailed(eq(id), any(), any(), eq(0));
        verifyNoInteractions(parserService);
    }

    @Test
    @DisplayName("tick: no-op when queue is empty")
    void tickNoOpWhenEmpty() {
        when(queueService.claimNext()).thenReturn(Optional.empty());

        worker.tick();

        verifyNoInteractions(parserService);
        verify(repository, never()).markDone(anyLong(), any(), any());
        verify(repository, never()).markFailed(anyLong(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("recoverStale: invokes repository with computed threshold")
    void recoverStaleInvokesRepository() {
        when(repository.recoverStale(any(), anyInt())).thenReturn(2);

        worker.recoverStale();

        verify(repository).recoverStale(any(), eq(3));
    }

    private OrinunoParseRequest baseRequest(long id, String json) {
        OrinunoParseRequest req = new OrinunoParseRequest();
        req.setId(id);
        req.setRequestJson(json);
        req.setRetryCount(0);
        return req;
    }
}
