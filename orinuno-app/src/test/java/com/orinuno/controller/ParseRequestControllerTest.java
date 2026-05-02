package com.orinuno.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.orinuno.model.ParseRequestPhase;
import com.orinuno.model.ParseRequestStatus;
import com.orinuno.model.dto.ParseRequestDtoView;
import com.orinuno.service.requestlog.ParseRequestService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.reactive.server.WebTestClient;

@ExtendWith(MockitoExtension.class)
class ParseRequestControllerTest {

    private WebTestClient webTestClient;

    @Mock private ParseRequestService parseRequestService;

    @BeforeEach
    void setUp() {
        ParseRequestController controller = new ParseRequestController(parseRequestService);
        webTestClient = WebTestClient.bindToController(controller).build();
    }

    @Test
    @DisplayName("POST returns 201 when a new request is created")
    void postReturns201ForNewRequest() {
        ParseRequestDtoView view = view(7L, ParseRequestStatus.PENDING, ParseRequestPhase.QUEUED);
        when(parseRequestService.submit(any(), any()))
                .thenReturn(new ParseRequestService.SubmitResult(view, true));

        webTestClient
                .post()
                .uri("/api/v1/parse/requests")
                .header("Content-Type", "application/json")
                .bodyValue("{\"title\":\"naruto\"}")
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(7);
    }

    @Test
    @DisplayName("POST returns 200 for an existing active request (idempotent)")
    void postReturns200ForExistingRequest() {
        ParseRequestDtoView view =
                view(11L, ParseRequestStatus.RUNNING, ParseRequestPhase.SEARCHING);
        when(parseRequestService.submit(any(), any()))
                .thenReturn(new ParseRequestService.SubmitResult(view, false));

        webTestClient
                .post()
                .uri("/api/v1/parse/requests")
                .header("Content-Type", "application/json")
                .bodyValue("{\"title\":\"naruto\"}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(11);
    }

    @Test
    @DisplayName("GET /{id} returns 404 when missing")
    void getByIdReturns404WhenMissing() {
        when(parseRequestService.findById(99L)).thenReturn(Optional.empty());

        webTestClient.get().uri("/api/v1/parse/requests/99").exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("GET / lists requests with X-Total-Count header")
    void listAddsTotalCountHeader() {
        ParseRequestDtoView v = view(1L, ParseRequestStatus.PENDING, ParseRequestPhase.QUEUED);
        when(parseRequestService.list(eq(ParseRequestStatus.PENDING), anyInt(), anyInt()))
                .thenReturn(new ParseRequestService.PageResult(List.of(v), 17L));

        webTestClient
                .get()
                .uri("/api/v1/parse/requests?status=PENDING&limit=10&offset=0")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("X-Total-Count", "17")
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("GET /?limit=0 returns count without rows (backpressure path)")
    void listLimitZeroReturnsHeadOnly() {
        when(parseRequestService.list(eq(ParseRequestStatus.PENDING), anyInt(), anyInt()))
                .thenReturn(new ParseRequestService.PageResult(List.of(), 42L));

        webTestClient
                .get()
                .uri("/api/v1/parse/requests?status=PENDING&limit=0")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("X-Total-Count", "42")
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(0);
    }

    private ParseRequestDtoView view(long id, ParseRequestStatus status, ParseRequestPhase phase) {
        return new ParseRequestDtoView(
                id, "abc", status, phase, 0, 0, List.of(), null, 0, null, null, null, null);
    }
}
