package com.orinuno.controller;

import com.orinuno.model.KodikEpisodeVariant;
import com.orinuno.repository.EpisodeVariantRepository;
import com.orinuno.service.KodikVideoDecoderService;
import com.orinuno.service.PlaywrightVideoFetcher;
import com.orinuno.service.VideoDownloadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.File;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/stream")
@RequiredArgsConstructor
@Tag(name = "Stream", description = "Video streaming proxy")
public class StreamController {

    private static final MediaType VIDEO_MP4 = MediaType.parseMediaType("video/mp4");
    private static final MediaType VIDEO_MP2T = MediaType.parseMediaType("video/mp2t");
    private static final int BUFFER_SIZE = 16 * 1024;

    private final EpisodeVariantRepository episodeVariantRepository;
    private final KodikVideoDecoderService decoderService;
    private final PlaywrightVideoFetcher playwrightFetcher;
    private final VideoDownloadService downloadService;
    private final WebClient kodikCdnWebClient;

    @GetMapping("/{variantId}")
    @Operation(summary = "Stream video — serves local file if downloaded, otherwise proxies from CDN")
    public Mono<Void> stream(@PathVariable Long variantId, ServerHttpRequest request,
                              ServerHttpResponse response) {
        return Mono.fromCallable(() -> episodeVariantRepository.findById(variantId))
                .flatMap(opt -> {
                    if (opt.isEmpty()) {
                        response.setStatusCode(HttpStatus.NOT_FOUND);
                        return response.setComplete();
                    }
                    KodikEpisodeVariant variant = opt.get();

                    if (variant.getLocalFilepath() != null) {
                        File localFile = Path.of(variant.getLocalFilepath()).toFile();
                        if (localFile.exists()) {
                            log.info("Streaming local file for variant id={}", variant.getId());
                            return streamLocalFile(localFile, request, response);
                        }
                    }

                    if (variant.getKodikLink() == null) {
                        response.setStatusCode(HttpStatus.BAD_REQUEST);
                        return response.setComplete();
                    }

                    if (playwrightFetcher.isAvailable()) {
                        log.info("No local file for variant {}, downloading via Playwright first", variantId);
                        return downloadService.downloadVariant(variantId)
                                .flatMap(state -> {
                                    if (state.status() == VideoDownloadService.DownloadStatus.COMPLETED
                                            && state.filepath() != null) {
                                        File downloaded = new File(state.filepath());
                                        if (downloaded.exists() && downloaded.length() > 0) {
                                            return streamLocalFile(downloaded, request, response);
                                        }
                                    }
                                    return decodeFreshUrl(variant)
                                            .flatMap(url -> proxyCdn(url, request, response));
                                });
                    }

                    return decodeFreshUrl(variant)
                            .flatMap(url -> proxyCdn(url, request, response));
                });
    }

    private Mono<Void> streamLocalFile(File file, ServerHttpRequest request,
                                        ServerHttpResponse response) {
        long fileLength = file.length();
        String rangeHeader = request.getHeaders().getFirst(HttpHeaders.RANGE);

        MediaType contentType = file.getName().endsWith(".ts") ? VIDEO_MP2T : VIDEO_MP4;
        response.getHeaders().setContentType(contentType);
        response.getHeaders().set(HttpHeaders.ACCEPT_RANGES, "bytes");

        long start = 0;
        long end = fileLength - 1;

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String[] ranges = rangeHeader.substring(6).split("-");
            start = Long.parseLong(ranges[0]);
            if (ranges.length > 1 && !ranges[1].isEmpty()) {
                end = Long.parseLong(ranges[1]);
            }
            response.setStatusCode(HttpStatus.PARTIAL_CONTENT);
            response.getHeaders().set(HttpHeaders.CONTENT_RANGE,
                    String.format("bytes %d-%d/%d", start, end, fileLength));
        }

        response.getHeaders().setContentLength(end - start + 1);

        return response.writeWith(
                DataBufferUtils.read(new FileSystemResource(file),
                        start, response.bufferFactory(), BUFFER_SIZE));
    }

    private Mono<String> decodeFreshUrl(KodikEpisodeVariant variant) {
        log.info("Decoding on-the-fly for variant id={}", variant.getId());
        return decoderService.decode(variant.getKodikLink())
                .map(this::pickBestQuality);
    }

    private String pickBestQuality(Map<String, String> videoLinks) {
        return videoLinks.entrySet().stream()
                .max(Comparator.comparingInt(e -> parseQuality(e.getKey())))
                .map(Map.Entry::getValue)
                .orElseThrow(() -> new RuntimeException("No video URLs decoded"));
    }

    private int parseQuality(String key) {
        try {
            return Integer.parseInt(key.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Mono<Void> proxyCdn(String videoUrl, ServerHttpRequest request,
                                 ServerHttpResponse response) {
        String rangeHeader = request.getHeaders().getFirst(HttpHeaders.RANGE);
        return proxyCdnWithRedirects(videoUrl, rangeHeader, response, 5);
    }

    private Mono<Void> proxyCdnWithRedirects(String url, String rangeHeader,
                                              ServerHttpResponse response, int maxRedirects) {
        if (maxRedirects <= 0) {
            response.setStatusCode(HttpStatus.BAD_GATEWAY);
            return response.setComplete();
        }

        WebClient.RequestHeadersSpec<?> spec = kodikCdnWebClient.get().uri(url);
        if (rangeHeader != null) {
            spec = spec.header(HttpHeaders.RANGE, rangeHeader);
        }

        return spec.exchangeToMono(cdnResponse -> {
            int status = cdnResponse.statusCode().value();

            if (status >= 300 && status < 400) {
                String location = cdnResponse.headers().asHttpHeaders().getFirst("Location");
                cdnResponse.releaseBody().subscribe();
                if (location == null) {
                    response.setStatusCode(HttpStatus.BAD_GATEWAY);
                    return response.setComplete();
                }
                if (location.startsWith("/")) {
                    try {
                        java.net.URI base = java.net.URI.create(url);
                        location = base.getScheme() + "://" + base.getHost() + location;
                    } catch (Exception ignored) {}
                }
                log.debug("CDN redirect {} -> {}", status, location.substring(0, Math.min(60, location.length())));
                return proxyCdnWithRedirects(location, rangeHeader, response, maxRedirects - 1);
            }

            response.setStatusCode(cdnResponse.statusCode());

            MediaType contentType = cdnResponse.headers().contentType()
                    .orElse(MediaType.APPLICATION_OCTET_STREAM);
            response.getHeaders().setContentType(contentType);

            cdnResponse.headers().contentLength()
                    .ifPresent(len -> response.getHeaders().setContentLength(len));

            String contentRange = cdnResponse.headers().asHttpHeaders()
                    .getFirst(HttpHeaders.CONTENT_RANGE);
            if (contentRange != null) {
                response.getHeaders().set(HttpHeaders.CONTENT_RANGE, contentRange);
            }

            response.getHeaders().set(HttpHeaders.ACCEPT_RANGES, "bytes");

            return response.writeWith(cdnResponse.body(
                    (inputMessage, context) -> inputMessage.getBody()));
        });
    }
}
