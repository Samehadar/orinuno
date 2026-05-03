package com.orinuno.controller;

import com.orinuno.service.provider.ProviderDecodeResult;
import com.orinuno.service.provider.aniboom.AniboomDecoderService;
import com.orinuno.service.provider.jutsu.JutsuDecoderService;
import com.orinuno.service.provider.sibnet.SibnetDecoderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Ad-hoc decoder sandbox for the non-Kodik providers (PLAYER-2/3/4). Useful for the demo UI to
 * verify that a given Sibnet / Aniboom / JutSu URL can be decoded against the current production
 * egress without touching the database.
 *
 * <p>Kodik decode lives under {@code POST /api/v1/parse/decode/variant/{id}} because it needs an
 * existing variant row. Provider sandbox is purposefully read-only and stateless.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/providers")
@RequiredArgsConstructor
@Tag(name = "Providers", description = "Ad-hoc decoder sandbox for Sibnet/Aniboom/JutSu")
public class ProvidersController {

    private final SibnetDecoderService sibnetDecoder;
    private final AniboomDecoderService aniboomDecoder;
    private final JutsuDecoderService jutsuDecoder;

    @PostMapping("/decode")
    @Operation(
            summary = "Decode a single provider URL ad-hoc",
            description =
                    "Routes the supplied URL to the matching provider decoder (Sibnet, Aniboom,"
                        + " JutSu). Stateless: no DB write, no caching. Use for demo / debugging —"
                        + " production decode goes through the orchestrator.")
    public Mono<ResponseEntity<ProviderDecodeResult>> decode(
            @Valid @RequestBody ProviderDecodeRequest request) {
        String provider = request.provider().trim().toUpperCase();
        String url = request.url().trim();
        log.info("Provider sandbox decode: provider={} url={}", provider, url);
        return switch (provider) {
            case "SIBNET" -> sibnetDecoder.decode(url).map(ResponseEntity::ok);
            case "ANIBOOM" -> aniboomDecoder.decode(url).map(ResponseEntity::ok);
            case "JUTSU" -> jutsuDecoder.decode(url).map(ResponseEntity::ok);
            default ->
                    Mono.just(
                            ResponseEntity.badRequest()
                                    .body(
                                            ProviderDecodeResult.failure(
                                                    "UNSUPPORTED_PROVIDER:" + provider)));
        };
    }

    public record ProviderDecodeRequest(@NotBlank String provider, @NotBlank String url) {}
}
