package com.orinuno.service;

import com.orinuno.model.KodikEpisodeVariant;
import com.orinuno.repository.EpisodeVariantRepository;
import java.net.URI;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class HlsManifestService {

    private final KodikVideoDecoderService decoderService;
    private final EpisodeVariantRepository episodeVariantRepository;
    private final ProxyWebClientService proxyWebClientService;
    private final WebClient kodikCdnWebClient;

    public Mono<HlsResult> getHlsUrl(Long variantId) {
        KodikEpisodeVariant variant = episodeVariantRepository.findById(variantId).orElse(null);
        if (variant == null) {
            return Mono.error(new RuntimeException("Variant not found: " + variantId));
        }
        if (variant.getKodikLink() == null) {
            return Mono.error(new RuntimeException("No kodik_link for variant: " + variantId));
        }

        return decoderService
                .decode(variant.getKodikLink())
                .map(
                        videoLinks -> {
                            String best = selectBestQuality(videoLinks);
                            if (best == null) {
                                throw new RuntimeException(
                                        "No video links decoded for variant: " + variantId);
                            }
                            String m3u8Url = toHlsUrl(best);
                            return new HlsResult(m3u8Url, null);
                        });
    }

    public Mono<HlsResult> getAbsolutizedManifest(Long variantId) {
        return getHlsUrl(variantId)
                .flatMap(
                        result -> {
                            String m3u8Url = result.url();
                            return proxyWebClientService
                                    .executeWithProxyFallback(
                                            kodikCdnWebClient,
                                            client ->
                                                    client.get()
                                                            .uri(m3u8Url)
                                                            .header(
                                                                    "Referer",
                                                                    "https://kodikplayer.com/")
                                                            .retrieve()
                                                            .bodyToMono(String.class))
                                    .map(
                                            rawManifest -> {
                                                String absolutized =
                                                        absolutizeManifest(rawManifest, m3u8Url);
                                                return new HlsResult(m3u8Url, absolutized);
                                            });
                        });
    }

    static String absolutizeManifest(String manifest, String manifestUrl) {
        String baseUrl = manifestUrl.substring(0, manifestUrl.lastIndexOf('/') + 1);
        StringBuilder result = new StringBuilder();

        for (String line : manifest.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                result.append(line).append("\n");
                continue;
            }

            if (trimmed.startsWith("http://")
                    || trimmed.startsWith("https://")
                    || trimmed.startsWith("//")) {
                result.append(line).append("\n");
            } else if (trimmed.startsWith("/")) {
                try {
                    URI uri = URI.create(manifestUrl);
                    result.append(uri.getScheme())
                            .append("://")
                            .append(uri.getHost())
                            .append(trimmed)
                            .append("\n");
                } catch (Exception e) {
                    result.append(baseUrl).append(trimmed).append("\n");
                }
            } else {
                result.append(baseUrl).append(trimmed).append("\n");
            }
        }

        return result.toString();
    }

    private static String toHlsUrl(String mp4Url) {
        if (mp4Url.contains(".m3u8")) return mp4Url;
        return mp4Url + ":hls:manifest.m3u8";
    }

    private String selectBestQuality(Map<String, String> videoLinks) {
        return videoLinks.entrySet().stream()
                .filter(e -> !e.getKey().startsWith("_"))
                .max(
                        (a, b) -> {
                            try {
                                return Integer.compare(
                                        Integer.parseInt(a.getKey()), Integer.parseInt(b.getKey()));
                            } catch (NumberFormatException e) {
                                return 0;
                            }
                        })
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    public record HlsResult(String url, String manifest) {}
}
