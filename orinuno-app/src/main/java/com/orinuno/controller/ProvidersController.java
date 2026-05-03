package com.orinuno.controller;

import com.orinuno.aniboom.AniboomClient;
import com.orinuno.jutsu.JutsuClient;
import com.orinuno.service.provider.ProviderDecodeResult;
import com.orinuno.service.provider.ProviderDecodeResults;
import com.orinuno.sibnet.SibnetClient;
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
 *
 * <p><strong>Step 4 of the API/module split (ADR 0014)</strong> rewires this controller directly
 * onto the per-provider SDK facades; the orinuno-app {@code *DecoderService} adapters are gone.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/providers")
@RequiredArgsConstructor
@Tag(name = "Providers", description = "Ad-hoc decoder sandbox for Sibnet/Aniboom/JutSu")
public class ProvidersController {

    private final SibnetClient sibnetClient;
    private final AniboomClient aniboomClient;
    private final JutsuClient jutsuClient;

    @PostMapping("/decode")
    @Operation(
            deprecated = true,
            summary = "[Deprecated] use POST /api/v1/sources/{provider}/decode",
            description =
                    "Legacy decoder dispatch kept only for backwards compatibility. The canonical"
                        + " path is POST /api/v1/sources/{provider}/decode where {provider} is one"
                        + " of kodik / sibnet / aniboom / jutsu and the request body is just"
                        + " {\"url\": \"...\"}. This endpoint will be removed after consumers"
                        + " migrate.")
    @Deprecated
    public Mono<ResponseEntity<ProviderDecodeResult>> decode(
            @Valid @RequestBody ProviderDecodeRequest request) {
        String provider = request.provider().trim().toUpperCase();
        String url = request.url().trim();
        log.info("Provider sandbox decode: provider={} url={}", provider, url);
        return switch (provider) {
            case "SIBNET" ->
                    sibnetClient
                            .decode(url)
                            .map(ProviderDecodeResults::from)
                            .map(ResponseEntity::ok);
            case "ANIBOOM" ->
                    aniboomClient
                            .decode(url)
                            .map(ProviderDecodeResults::from)
                            .map(ResponseEntity::ok);
            case "JUTSU" ->
                    jutsuClient
                            .decode(url)
                            .map(ProviderDecodeResults::from)
                            .map(ResponseEntity::ok);
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
