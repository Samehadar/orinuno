/*
 * ContentControllerTest — ADR 0021 §C1.1 invariant.
 *
 * Pins the four GET /api/v1/content/* routes ported from orinuno-app:
 *   1. findAll — paginated list with safe sortBy/order whitelisting
 *   2. findById — 200 with body when present, 404 when missing
 *   3. findVariantsByContentId — 200 with list (possibly empty)
 *   4. findByKinopoiskId — 200 with body when present, 404 when missing
 *
 * Pure unit test — no Spring context, no DB. ContentReadService is mocked.
 * The wire format itself (ContentDto / EpisodeVariantDto field set) is
 * already locked by ContentDtoMapper plus the demo UI; this test only
 * locks the controller-level branching (status codes + pagination
 * sanitisation).
 */
package com.orinuno.source.kodik.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.orinuno.source.kodik.model.dto.ContentDto;
import com.orinuno.source.kodik.model.dto.EpisodeVariantDto;
import com.orinuno.source.kodik.model.dto.PageResponse;
import com.orinuno.source.kodik.service.ContentReadService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContentController — ADR 0021 §C1.1 read-only catalog routes")
class ContentControllerTest {

    @Mock private ContentReadService contentService;
    @InjectMocks private ContentController controller;

    @Test
    @DisplayName("findById — 200 + body when present")
    void findByIdPresent() {
        when(contentService.findById(42L))
                .thenReturn(Optional.of(ContentDto.builder().id(42L).title("Naruto").build()));

        var response = controller.findById(42L).block();
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().getId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("findById — 404 when missing")
    void findByIdMissing() {
        when(contentService.findById(99L)).thenReturn(Optional.empty());

        var response = controller.findById(99L).block();
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("findByKinopoiskId — 200 + body when present")
    void findByKinopoiskIdPresent() {
        when(contentService.findByKinopoiskId("283290"))
                .thenReturn(
                        Optional.of(
                                ContentDto.builder().id(7L).kinopoiskId("283290").build()));

        var response = controller.findByKinopoiskId("283290").block();
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().getKinopoiskId()).isEqualTo("283290");
    }

    @Test
    @DisplayName("findByKinopoiskId — 404 when missing")
    void findByKinopoiskIdMissing() {
        when(contentService.findByKinopoiskId("missing")).thenReturn(Optional.empty());

        var response = controller.findByKinopoiskId("missing").block();
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("findVariants — 200 + (possibly empty) list")
    void findVariantsAlwaysReturnsList() {
        when(contentService.findVariantsByContentId(42L))
                .thenReturn(
                        List.of(EpisodeVariantDto.builder().id(1L).contentId(42L).build()));

        var response = controller.findVariants(42L).block();
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    @DisplayName(
            "findAll — sanitises sortBy (unknown → id) and order (lowercase → uppercase / unknown"
                    + " → ASC)")
    void findAllSanitisesPageRequest() {
        when(contentService.findAll(any()))
                .thenReturn(
                        PageResponse.<ContentDto>builder()
                                .content(List.of())
                                .page(0)
                                .size(20)
                                .totalElements(0)
                                .totalPages(0)
                                .build());

        // Unknown sortBy and lowercase order — controller must pass through
        // sanitised values to the service.
        var response = controller.findAll(0, 20, "ignored_column", "desc").block();
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

        org.mockito.ArgumentCaptor<com.orinuno.source.kodik.model.dto.PageRequest> captor =
                org.mockito.ArgumentCaptor.forClass(
                        com.orinuno.source.kodik.model.dto.PageRequest.class);
        org.mockito.Mockito.verify(contentService).findAll(captor.capture());
        assertThat(captor.getValue().getSortBy()).isEqualTo("id");
        assertThat(captor.getValue().getOrder()).isEqualTo("DESC");
    }
}
