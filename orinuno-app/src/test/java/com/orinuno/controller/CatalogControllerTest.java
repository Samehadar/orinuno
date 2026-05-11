/*
 * CatalogControllerTest — ADR 0018 Phase 5.7 invariant.
 *
 * Locks the public REST surface:
 *
 *   GET /api/v1/catalog/content/{id}
 *     200 + JSON body  → row found
 *     404              → no such id
 *
 * Pure unit test — no Spring MVC context, just the controller method directly.
 * The HTTP routing layer is plain Spring annotations validated elsewhere.
 */
package com.orinuno.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.orinuno.catalog.readonly.CatalogContentRow;
import com.orinuno.catalog.readonly.CatalogReadCache;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogController — ADR 0018 Phase 5.7 read-only catalog surface")
class CatalogControllerTest {

    @Mock private CatalogReadCache cache;

    @Test
    @DisplayName("present row → 200 + body")
    void presentReturns200() {
        CatalogContentRow row =
                new CatalogContentRow(
                        7L,
                        "Кризис",
                        "Crisis",
                        "movie",
                        2026,
                        "4242",
                        null,
                        "tt99",
                        "4242424242",
                        null,
                        null,
                        LocalDateTime.now(),
                        LocalDateTime.now());
        when(cache.findById(7L)).thenReturn(Optional.of(row));

        CatalogController controller = new CatalogController(cache);
        ResponseEntity<CatalogContentRow> response = controller.findById(7L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(row);
    }

    @Test
    @DisplayName("missing row → 404")
    void missingReturns404() {
        when(cache.findById(999L)).thenReturn(Optional.empty());

        CatalogController controller = new CatalogController(cache);
        ResponseEntity<CatalogContentRow> response = controller.findById(999L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }
}
