package com.orinuno.aksor.host.yummy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orinuno.aksor.AksorConfig;
import com.orinuno.aksor.AksorErrorCodes;
import com.orinuno.aksor.AksorException;
import com.orinuno.aksor.drift.AksorDriftDetector;
import com.orinuno.aksor.drift.AksorDriftSignal;
import com.orinuno.aksor.host.AksorHostPageParser;
import com.orinuno.aksor.model.AksorAnime;
import com.orinuno.aksor.model.AksorEpisode;
import com.orinuno.aksor.model.AksorSkipMark;
import com.orinuno.aksor.parser.AksorHashParser;
import jakarta.annotation.Nullable;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Resolves {@code old.yummyani.me/catalog/item/<slug>} pages into an {@link AksorAnime}. Two steps:
 *
 * <ol>
 *   <li>Fetch the page HTML and read {@code data-id="<animeId>"} from the document. Title + poster
 *       come from {@code og:image} / {@code og:title} meta tags.
 *   <li>Call {@code /api/anime/{animeId}/videos} and filter out non-Aksor entries. Each Aksor entry
 *       exposes an {@code iframe_url} pointing at {@code player.aksor.tv/video/<hash>}; the hash is
 *       what drives the second-hop {@code AksorApiClient} call.
 * </ol>
 *
 * <p>HTTP is done with {@link HttpClient} (not Reactor Netty) — Reactor Netty stalls on the same
 * Tengine-backed hosts where cvh-downloader-sdk had the same issue with vkuser.net. We keep the
 * {@code WebClient.Builder} parameter for API symmetry but ignore it.
 */
@Slf4j
public final class YummyAniHost implements AksorHostPageParser {

    private static final String AKSOR_PLAYER_NAME = "плеер aksor";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AksorConfig config;
    private final HttpClient httpClient;
    private final AksorDriftDetector drift;

    @SuppressWarnings("unused")
    public YummyAniHost(AksorConfig config, WebClient.Builder webClientBuilder) {
        this(config, defaultJdkClient(config), AksorDriftDetector.disabled());
    }

    public YummyAniHost(
            AksorConfig config, WebClient.Builder webClientBuilder, AksorDriftDetector drift) {
        this(config, defaultJdkClient(config), drift);
    }

    YummyAniHost(AksorConfig config, HttpClient httpClient) {
        this(config, httpClient, AksorDriftDetector.disabled());
    }

    YummyAniHost(AksorConfig config, HttpClient httpClient, AksorDriftDetector drift) {
        this.config = config;
        this.httpClient = httpClient;
        this.drift = drift == null ? AksorDriftDetector.disabled() : drift;
    }

    private static HttpClient defaultJdkClient(AksorConfig config) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.requestTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String hostId() {
        return "yummyani";
    }

    @Override
    public boolean supports(URI pageUrl) {
        if (pageUrl == null || pageUrl.getHost() == null) {
            return false;
        }
        String host = pageUrl.getHost().toLowerCase();
        return host.equals("yummyani.me")
                || host.equals("old.yummyani.me")
                || host.endsWith(".yummyani.me");
    }

    @Override
    public Mono<AksorAnime> resolve(String pageUrl) {
        return fetchHtml(pageUrl)
                .flatMap(
                        html -> {
                            PageMeta meta = parsePage(html, pageUrl);
                            if (meta.animeId() == null) {
                                drift.record(
                                        AksorDriftSignal.YUMMY_PAGE_NO_ANIME_ID,
                                        java.util.Map.of("hostId", hostId(), "pageUrl", pageUrl));
                                return Mono.error(
                                        new AksorException(
                                                AksorErrorCodes.AKSOR_PAGE_PARSE_ERROR,
                                                "yummyani page lacks data-id"));
                            }
                            return fetchVideos(pageUrl, meta);
                        });
    }

    // ---------------- HTML

    private Mono<String> fetchHtml(String pageUrl) {
        return Mono.fromCallable(() -> fetchSync(pageUrl, "text/html,application/xhtml+xml", null))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        ex ->
                                ex instanceof AksorException
                                        ? ex
                                        : new AksorException(
                                                AksorErrorCodes.AKSOR_FETCH_ERROR,
                                                "yummyani page fetch failed: " + ex.getMessage(),
                                                ex));
    }

    private String fetchSync(String url, String accept, @Nullable String referer) throws Exception {
        HttpRequest.Builder b =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", config.userAgent())
                        .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                        .header("Accept", accept)
                        .timeout(Duration.ofSeconds(config.requestTimeoutSeconds()))
                        .GET();
        if (referer != null && !referer.isBlank()) {
            b.header("Referer", referer);
        }
        HttpResponse<String> response =
                httpClient.send(
                        b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AksorException(
                    AksorErrorCodes.AKSOR_FETCH_ERROR,
                    "yummyani fetch HTTP " + response.statusCode() + " for " + url);
        }
        return response.body();
    }

    static PageMeta parsePage(String html, String pageUrl) {
        Document doc = Jsoup.parse(html == null ? "" : html, pageUrl == null ? "" : pageUrl);
        String animeId = firstDataId(doc);
        String title = metaContent(doc, "og:title");
        String posterUrl = absoluteMeta(doc, "og:image");
        String slug = extractSlug(pageUrl);
        return new PageMeta(animeId, slug, title, posterUrl);
    }

    @Nullable
    private static String firstDataId(Document doc) {
        for (Element el : doc.select("[data-id]")) {
            String id = el.attr("data-id").trim();
            if (id.matches("\\d+")) {
                return id;
            }
        }
        return null;
    }

    @Nullable
    private static String metaContent(Document doc, String property) {
        Element el = doc.selectFirst("meta[property=" + property + "]");
        if (el == null) {
            return null;
        }
        String c = el.attr("content").trim();
        return c.isEmpty() ? null : c;
    }

    @Nullable
    private static String absoluteMeta(Document doc, String property) {
        Element el = doc.selectFirst("meta[property=" + property + "]");
        if (el == null) {
            return null;
        }
        String abs = el.absUrl("content");
        return abs.isEmpty() ? metaContent(doc, property) : abs;
    }

    @Nullable
    private static String extractSlug(String pageUrl) {
        if (pageUrl == null || pageUrl.isBlank()) {
            return null;
        }
        String noQuery = pageUrl.split("\\?", 2)[0];
        if (noQuery.endsWith("/")) {
            noQuery = noQuery.substring(0, noQuery.length() - 1);
        }
        int slash = noQuery.lastIndexOf('/');
        return slash >= 0 ? noQuery.substring(slash + 1) : noQuery;
    }

    // ---------------- /api/anime/{id}/videos

    private Mono<AksorAnime> fetchVideos(String pageUrl, PageMeta meta) {
        String origin = pageOrigin(pageUrl);
        String path =
                "/api/anime/"
                        + URLEncoder.encode(meta.animeId(), StandardCharsets.UTF_8)
                        + "/videos";
        String url = origin + path;
        return Mono.fromCallable(() -> fetchSync(url, "application/json", pageUrl))
                .subscribeOn(Schedulers.boundedElastic())
                .map(json -> buildAnime(meta, pageUrl, json, drift))
                .onErrorMap(
                        ex ->
                                ex instanceof AksorException
                                        ? ex
                                        : new AksorException(
                                                AksorErrorCodes.AKSOR_FETCH_ERROR,
                                                "yummyani videos API failed: " + ex.getMessage(),
                                                ex));
    }

    static AksorAnime buildAnime(PageMeta meta, String pageUrl, String videosJson) {
        return buildAnime(meta, pageUrl, videosJson, AksorDriftDetector.disabled());
    }

    static AksorAnime buildAnime(
            PageMeta meta, String pageUrl, String videosJson, AksorDriftDetector drift) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(videosJson);
            JsonNode response = root.path("response");
            if (!response.isArray()) {
                drift.record(
                        AksorDriftSignal.YUMMY_VIDEOS_RESPONSE_NOT_ARRAY,
                        java.util.Map.of("animeId", String.valueOf(meta.animeId())));
                throw new AksorException(
                        AksorErrorCodes.AKSOR_PAGE_PARSE_ERROR,
                        "yummyani videos response is not an array");
            }
            List<AksorEpisode> episodes = new ArrayList<>();
            for (JsonNode entry : response) {
                AksorEpisode ep = mapEpisode(entry, drift);
                if (ep != null) {
                    episodes.add(ep);
                }
            }
            if (episodes.isEmpty()) {
                throw new AksorException(
                        AksorErrorCodes.AKSOR_NO_EPISODES, "no Aksor episodes on yummyani page");
            }
            return new AksorAnime(
                    meta.animeId(), meta.slug(), pageUrl, meta.title(), meta.posterUrl(), episodes);
        } catch (AksorException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AksorException(
                    AksorErrorCodes.AKSOR_PAGE_PARSE_ERROR,
                    "yummyani videos JSON parse failed: " + ex.getMessage(),
                    ex);
        }
    }

    @Nullable
    private static AksorEpisode mapEpisode(JsonNode entry, AksorDriftDetector drift) {
        String iframeUrl = textOrNull(entry.path("iframe_url"));
        String player = textOrNull(entry.path("data").path("player"));
        String dubbing = textOrNull(entry.path("data").path("dubbing"));
        if (player == null) {
            return null;
        }
        if (!player.toLowerCase().contains(AKSOR_PLAYER_NAME)) {
            drift.record(
                    AksorDriftSignal.YUMMY_EPISODE_UNKNOWN_PLAYER,
                    java.util.Map.of("player", player));
            return null;
        }
        String hash = AksorHashParser.extract(iframeUrl).orElse(null);
        if (hash == null) {
            drift.record(
                    AksorDriftSignal.YUMMY_EPISODE_NO_HASH,
                    java.util.Map.of(
                            "iframeUrl",
                            iframeUrl == null ? "(null)" : iframeUrl,
                            "videoId",
                            entry.path("video_id").asText("(unknown)")));
            return null;
        }
        JsonNode skips = entry.path("skips");
        return new AksorEpisode(
                entry.path("video_id").isNumber() ? entry.path("video_id").asLong() : null,
                textOrNull(entry.path("number")),
                dubbing,
                player,
                hash,
                iframeUrl,
                entry.path("duration").isNumber() ? entry.path("duration").asInt() : null,
                parseSkip(skips.path("opening")),
                parseSkip(skips.path("ending")),
                null);
    }

    @Nullable
    private static AksorSkipMark parseSkip(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        Integer time = node.path("time").isNumber() ? node.path("time").asInt() : null;
        Integer length = node.path("length").isNumber() ? node.path("length").asInt() : null;
        if (time == null && length == null) {
            return null;
        }
        return new AksorSkipMark(time, length);
    }

    private static String pageOrigin(String pageUrl) {
        try {
            URI uri = URI.create(pageUrl);
            return uri.getScheme() + "://" + uri.getHost();
        } catch (Exception ex) {
            return "";
        }
    }

    @Nullable
    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String v = node.asText("").trim();
        return v.isEmpty() ? null : v;
    }

    record PageMeta(
            @Nullable String animeId,
            @Nullable String slug,
            @Nullable String title,
            @Nullable String posterUrl) {}
}
