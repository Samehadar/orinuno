package com.orinuno.controller;

import com.orinuno.client.embed.KodikEmbedException;
import com.orinuno.client.embed.KodikIdType;
import com.orinuno.model.dto.EmbedLinkDto;
import com.orinuno.service.KodikEmbedService;
import com.orinuno.token.KodikTokenException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Resolves a Kodik embed (iframe) URL by external id without going through the parser pipeline.
 * Lightweight wrapper over Kodik's {@code /get-player} helper — does NOT touch the database, the
 * decoder, or the request log. Useful for "give me an iframe URL right now" scenarios such as the
 * IDEA-CAL-3 ContentDetail "Next episode" widget or external embedders that have only an external
 * id and want to drop a player onto a page.
 *
 * <p>Distinct from {@code POST /api/v1/parse/search} on purpose: the parser flow ingests, persists
 * and decodes; this flow only resolves a URL. If you need to download / decode / persist, use
 * {@code /api/v1/parse/search}.
 *
 * <p>Status code mapping (kept parallel to {@link com.orinuno.controller.HlsController}):
 *
 * <ul>
 *   <li>{@code 200} — embed link resolved
 *   <li>{@code 400} — unknown {@code idType} or empty {@code id}
 *   <li>{@code 404} — Kodik returned {@code "found": false}
 *   <li>{@code 401} — missing / wrong {@code X-API-KEY} (when {@code orinuno.security.api-key} is
 *       configured)
 *   <li>{@code 502} — Kodik returned an upstream error or malformed response
 *   <li>{@code 503} — token registry is empty / every token rejected
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/embed")
@RequiredArgsConstructor
@Tag(
        name = "Embed",
        description =
                "Resolve a Kodik player iframe URL by external id (shikimori, kinopoisk, imdb,"
                        + " mdl, kodik, worldart_animation, worldart_cinema). Lightweight, no DB"
                        + " write.")
public class KodikEmbedController {

    private final KodikEmbedService embedService;

    @GetMapping("/{idType}/{id}")
    @Operation(
            summary = "Resolve a Kodik embed iframe URL by external id",
            description =
                    "Calls Kodik's /get-player helper and returns the resolved kodikplayer.com"
                        + " iframe URL plus minimal metadata. The endpoint is read-only — it does"
                        + " not write to the database and does not trigger the video decoder. Use"
                        + " /api/v1/parse/search if you also want to ingest the content.\n\n"
                        + "**Supported idType values (snake_case or kebab-case accepted):**\n"
                        + "- `shikimori` — Shikimori id (numeric, e.g. `20`)\n"
                        + "- `kinopoisk` — Kinopoisk id (numeric)\n"
                        + "- `imdb` — IMDb id; the `tt` prefix is added automatically when"
                        + " missing\n"
                        + "- `mdl` — MyDramaList id\n"
                        + "- `kodik` — native Kodik id, must be supplied as `serial-{n}` or"
                        + " `movie-{n}`\n"
                        + "- `worldart_animation` — World Art animation id\n"
                        + "- `worldart_cinema` — World Art cinema id\n\n"
                        + "Auth: subject to `X-API-KEY` when `orinuno.security.api-key` is"
                        + " configured.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Embed resolved",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = EmbedLinkDto.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Unknown idType or empty id",
                content = @Content(mediaType = "application/json")),
        @ApiResponse(
                responseCode = "404",
                description = "Kodik has no player for the supplied id",
                content = @Content(mediaType = "application/json")),
        @ApiResponse(
                responseCode = "502",
                description = "Kodik returned a non-recoverable upstream error",
                content = @Content(mediaType = "application/json")),
        @ApiResponse(
                responseCode = "503",
                description = "No working Kodik token available",
                content = @Content(mediaType = "application/json"))
    })
    public Mono<ResponseEntity<Object>> resolve(
            @Parameter(description = "External id source", example = "shikimori") @PathVariable
                    String idType,
            @Parameter(description = "External id value", example = "20") @PathVariable String id) {
        KodikIdType type;
        try {
            type = KodikIdType.fromSlug(idType);
        } catch (IllegalArgumentException ex) {
            log.debug("Bad idType '{}': {}", idType, ex.getMessage());
            return Mono.just(error(HttpStatus.BAD_REQUEST, ex.getMessage()));
        }

        if (id == null || id.isBlank()) {
            return Mono.just(error(HttpStatus.BAD_REQUEST, "id must not be blank"));
        }

        return embedService
                .resolve(type, id)
                .<ResponseEntity<Object>>map(dto -> ResponseEntity.ok().body(dto))
                .onErrorResume(IllegalArgumentException.class, this::badRequest)
                .onErrorResume(KodikEmbedException.NotFoundException.class, this::notFound)
                .onErrorResume(KodikEmbedException.UpstreamException.class, this::badGateway)
                .onErrorResume(
                        KodikEmbedException.MalformedResponseException.class, this::badGateway)
                .onErrorResume(
                        KodikTokenException.NoWorkingTokenException.class, this::serviceUnavailable)
                .onErrorResume(
                        KodikTokenException.TokenRejectedException.class, this::serviceUnavailable);
    }

    private Mono<ResponseEntity<Object>> badRequest(Exception ex) {
        log.debug("Embed resolve bad-request: {}", ex.getMessage());
        return Mono.just(error(HttpStatus.BAD_REQUEST, ex.getMessage()));
    }

    private Mono<ResponseEntity<Object>> notFound(Exception ex) {
        return Mono.just(error(HttpStatus.NOT_FOUND, ex.getMessage()));
    }

    private Mono<ResponseEntity<Object>> badGateway(Exception ex) {
        log.warn("Embed resolve upstream issue: {}", ex.getMessage());
        return Mono.just(error(HttpStatus.BAD_GATEWAY, ex.getMessage()));
    }

    private Mono<ResponseEntity<Object>> serviceUnavailable(Exception ex) {
        log.warn("Embed resolve token issue: {}", ex.getMessage());
        return Mono.just(error(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage()));
    }

    private static ResponseEntity<Object> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
