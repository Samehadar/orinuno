package com.orinuno.controller;

import com.orinuno.aniboom.AniboomClient;
import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.jutsu.JutsuClient;
import com.orinuno.service.KodikVideoDecoderService;
import com.orinuno.service.provider.ProviderDecodeResult;
import com.orinuno.service.provider.ProviderDecodeResults;
import com.orinuno.sibnet.SibnetClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Per-source API surface (Step 1 of the API/module split — see ADR 0001 follow-up).
 *
 * <p>This controller owns the {@code /api/v1/sources/...} branch that gives external consumers a
 * stable, provider-aware entry point:
 *
 * <ul>
 *   <li>{@code GET /api/v1/sources} — capabilities listing (which providers we support, what
 *       operations they expose, whether credentials are configured).
 *   <li>{@code POST /api/v1/sources/{provider}/decode} — stateless ad-hoc decode dispatch for Kodik
 *       / Sibnet / Aniboom / JutSu. Replaces the old {@code POST /api/v1/providers/decode} (which
 *       stays as a deprecated alias).
 * </ul>
 *
 * <p>The streaming proxy lives in {@link JutsuStreamProxyController} but is also reachable under
 * {@code /api/v1/sources/jutsu/stream} (canonical). Ranked candidates for an episode live at {@code
 * GET /api/v1/anime/{contentId}/episodes/{season}/{episode}/sources} on {@link
 * MultiSourceController}.
 *
 * <p><strong>Step 4 of the API/module split (ADR 0014)</strong> rewires this controller directly on
 * top of the per-provider SDK facades ({@link JutsuClient}, {@link SibnetClient}, {@link
 * AniboomClient}). The previous orinuno-app {@code *DecoderService} adapter shim is gone — the
 * SDK→{@link ProviderDecodeResult} translation lives in the {@link ProviderDecodeResults} static
 * helper so it stays testable in isolation.
 *
 * <p><strong>Auth</strong>: kept out of {@code ApiKeyAuthFilter}'s gate intentionally. The provider
 * sandbox is the same one the demo UI hits without an API key, and external consumers can call it
 * from a browser without a server-side hop.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sources")
@Tag(name = "Sources", description = "Per-source capabilities and ad-hoc decoder sandbox")
public class SourcesController {

    private final KodikVideoDecoderService kodikDecoder;
    private final SibnetClient sibnetClient;
    private final AniboomClient aniboomClient;
    private final JutsuClient jutsuClient;
    private final OrinunoProperties properties;

    public SourcesController(
            KodikVideoDecoderService kodikDecoder,
            SibnetClient sibnetClient,
            AniboomClient aniboomClient,
            JutsuClient jutsuClient,
            OrinunoProperties properties) {
        this.kodikDecoder = kodikDecoder;
        this.sibnetClient = sibnetClient;
        this.aniboomClient = aniboomClient;
        this.jutsuClient = jutsuClient;
        this.properties = properties;
    }

    @GetMapping
    @Operation(
            summary = "List supported video sources and their capabilities",
            description =
                    "Returns one entry per provider with the operations it exposes, whether"
                            + " credentials are required for premium content, and whether they are"
                            + " currently configured. Safe to call without authentication.")
    public ResponseEntity<Map<String, Object>> capabilities() {
        boolean jutsuCredsConfigured = properties.getProviders().getJutsu().hasCredentials();
        List<Map<String, Object>> providers =
                List.of(
                        provider(
                                "kodik",
                                "Kodik",
                                "Russian-language anime/series/movies aggregator. Primary source.",
                                List.of("search", "list", "embed", "decode", "calendar"),
                                false,
                                false,
                                "Token-driven; configured via KODIK_TOKEN env var. See"
                                        + " /api/v1/parse and /api/v1/kodik for the full surface."),
                        jutsuCapabilities(jutsuCredsConfigured),
                        provider(
                                "sibnet",
                                "Sibnet",
                                "Russian video host (video.sibnet.ru) used as fallback player.",
                                List.of("decode"),
                                false,
                                false,
                                "Stateless — no credentials needed."),
                        provider(
                                "aniboom",
                                "Aniboom",
                                "Aniboom embed player. Often geo-fenced; CIS egress recommended.",
                                List.of("decode"),
                                false,
                                false,
                                "Stateless — no credentials needed. Some episodes are"
                                        + " geo-restricted."));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("providers", providers);
        body.put("count", providers.size());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{provider}/decode")
    @Operation(
            summary = "Decode a single source URL ad-hoc",
            description =
                    "Stateless decoder dispatch keyed by path segment. Supported providers: kodik,"
                            + " sibnet, aniboom, jutsu. Returns a uniform ProviderDecodeResult with"
                            + " {success, qualities, format, errorCode}. No DB write, no caching.")
    public Mono<ResponseEntity<ProviderDecodeResult>> decode(
            @PathVariable String provider, @Valid @RequestBody DecodeRequest request) {
        String key = provider == null ? "" : provider.trim().toUpperCase();
        String url = request.url().trim();
        log.info("Per-source decode dispatch: provider={} url={}", key, url);
        return switch (key) {
            case "KODIK" ->
                    kodikDecoder
                            .decode(url)
                            .map(
                                    qualities ->
                                            ResponseEntity.ok(
                                                    ProviderDecodeResult.success(
                                                            qualities, "application/x-mpegURL")))
                            .onErrorResume(
                                    ex -> {
                                        log.warn(
                                                "Kodik sandbox decode failed for {}: {}",
                                                url,
                                                ex.toString());
                                        return Mono.just(
                                                ResponseEntity.ok(
                                                        ProviderDecodeResult.failure(
                                                                "KODIK_DECODE_ERROR")));
                                    });
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
                                                    "UNSUPPORTED_PROVIDER:" + key)));
        };
    }

    /**
     * jut.su entry for the capabilities listing. Pulled out into its own helper so the live drift
     * snapshot is wired in cleanly: dashboards and orchestrators can read {@code /api/v1/sources}
     * alone and immediately see whether jut.su is currently HEALTHY / DEGRADED without making a
     * second call to {@code /api/v1/sources/jutsu/drift}.
     */
    private Map<String, Object> jutsuCapabilities(boolean credentialsConfigured) {
        Map<String, Object> p =
                provider(
                        "jutsu",
                        "JutSu",
                        "JutSu free-and-premium anime player. Premium content needs a jut.su+"
                                + " account.",
                        List.of(
                                "catalog",
                                "search",
                                "anime-info",
                                "episode-meta",
                                "notice-feed",
                                "decode",
                                "stream",
                                "drift-health"),
                        true,
                        credentialsConfigured,
                        credentialsConfigured
                                ? "JUTSU_USERNAME / JUTSU_PASSWORD configured — premium content"
                                        + " will be decoded automatically."
                                : "No JUTSU_USERNAME / JUTSU_PASSWORD set — premium episodes"
                                        + " will return JUTSU_PREMIUM_REQUIRED.");
        // Live drift signal so callers don't need a second round-trip.
        p.put("driftHealth", jutsuClient.getDriftSnapshot().health().name());
        p.put("driftLifetimeEvents", jutsuClient.getDriftSnapshot().lifetimeEvents());
        return p;
    }

    private static Map<String, Object> provider(
            String id,
            String displayName,
            String description,
            List<String> operations,
            boolean credentialsRequired,
            boolean credentialsConfigured,
            String notes) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", id);
        p.put("displayName", displayName);
        p.put("description", description);
        p.put("operations", operations);
        p.put("credentialsRequired", credentialsRequired);
        p.put("credentialsConfigured", credentialsConfigured);
        p.put("notes", notes);
        return p;
    }

    public record DecodeRequest(@NotBlank String url) {}
}
