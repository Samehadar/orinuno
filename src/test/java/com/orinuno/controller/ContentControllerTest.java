package com.orinuno.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.orinuno.model.dto.ContentDto;
import com.orinuno.model.dto.PageResponse;
import com.orinuno.service.ContentService;
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
class ContentControllerTest {

    private WebTestClient webTestClient;

    @Mock private ContentService contentService;

    @BeforeEach
    void setUp() {
        ContentController controller = new ContentController(contentService);
        webTestClient = WebTestClient.bindToController(controller).build();
    }

    @Test
    @DisplayName("GET /api/v1/content should return paginated list")
    void shouldReturnContentList() {
        ContentDto dto =
                ContentDto.builder().id(1L).title("Test Movie").type("movie").year(2024).build();

        PageResponse<ContentDto> page =
                PageResponse.<ContentDto>builder()
                        .content(List.of(dto))
                        .page(0)
                        .size(20)
                        .totalElements(1)
                        .totalPages(1)
                        .build();

        when(contentService.findAll(any())).thenReturn(page);

        webTestClient
                .get()
                .uri("/api/v1/content")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content[0].title")
                .isEqualTo("Test Movie")
                .jsonPath("$.totalElements")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("GET /api/v1/content/{id} should return content by ID")
    void shouldReturnContentById() {
        ContentDto dto = ContentDto.builder().id(1L).title("Test").type("movie").build();

        when(contentService.findById(1L)).thenReturn(Optional.of(dto));

        webTestClient
                .get()
                .uri("/api/v1/content/1")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.title")
                .isEqualTo("Test");
    }

    @Test
    @DisplayName("GET /api/v1/content/{id} should return 404 for non-existent")
    void shouldReturn404ForNonExistent() {
        when(contentService.findById(999L)).thenReturn(Optional.empty());

        webTestClient.get().uri("/api/v1/content/999").exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("GET /api/v1/content/by-kinopoisk/{id} should find by kinopoisk ID")
    void shouldFindByKinopoiskId() {
        ContentDto dto = ContentDto.builder().id(1L).title("Test").kinopoiskId("123456").build();

        when(contentService.findByKinopoiskId("123456")).thenReturn(Optional.of(dto));

        webTestClient
                .get()
                .uri("/api/v1/content/by-kinopoisk/123456")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.kinopoiskId")
                .isEqualTo("123456");
    }
}
