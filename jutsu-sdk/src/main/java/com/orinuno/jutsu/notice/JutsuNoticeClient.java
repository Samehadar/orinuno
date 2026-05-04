package com.orinuno.jutsu.notice;

import com.orinuno.jutsu.JutsuConfig;
import com.orinuno.jutsu.auth.JutsuSessionManager;
import com.orinuno.jutsu.drift.JutsuDriftDetector;
import com.orinuno.jutsu.drift.JutsuDriftSignal;
import com.orinuno.jutsu.drift.JutsuParserContext;
import com.orinuno.jutsu.parser.JutsuHtmlCharset;
import com.orinuno.jutsu.ratelimit.JutsuRateLimiter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive client for jut.su's notice feed: {@code POST /engine/ajax/site_notice.php} with body
 * {@code action=show&notice_id={cursor}}.
 *
 * <p>Each call returns 50 entries (newest in the page first). The endpoint returns an empty body at
 * the history bound (no more older entries available).
 *
 * <p>The client is thin and stateless. Like other SDK clients it shares the rate limiter, session
 * manager and drift detector with the rest of the SDK so cross-endpoint backpressure stays
 * coherent.
 */
@Slf4j
public final class JutsuNoticeClient {

    /** AJAX endpoint path. */
    static final String NOTICE_PATH = "/engine/ajax/site_notice.php";

    /** Pattern that extracts the latest notice cursor from the homepage's down-arrow onclick. */
    private static final Pattern LATEST_CURSOR_PATTERN =
            Pattern.compile("show_top_notice\\(\\s*(\\d+)\\s*\\)");

    private final WebClient ajaxClient;
    private final WebClient pageClient;
    private final JutsuRateLimiter rateLimiter;
    private final JutsuSessionManager sessionManager;
    private final JutsuDriftDetector driftDetector;
    private final String baseUrl;

    public JutsuNoticeClient(
            JutsuConfig config,
            JutsuRateLimiter rateLimiter,
            JutsuSessionManager sessionManager,
            JutsuDriftDetector driftDetector,
            WebClient.Builder webClientBuilder) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        if (rateLimiter == null) throw new IllegalArgumentException("rateLimiter must not be null");
        if (sessionManager == null) {
            throw new IllegalArgumentException("sessionManager must not be null");
        }
        if (driftDetector == null) {
            throw new IllegalArgumentException("driftDetector must not be null");
        }
        if (webClientBuilder == null) {
            throw new IllegalArgumentException("webClientBuilder must not be null");
        }
        this.ajaxClient =
                webClientBuilder
                        .clone()
                        .defaultHeader(HttpHeaders.USER_AGENT, config.userAgent())
                        .defaultHeader(HttpHeaders.ACCEPT, "*/*")
                        .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "ru-RU,ru;q=0.9,en;q=0.8")
                        .defaultHeader(HttpHeaders.ORIGIN, config.baseUrl())
                        .defaultHeader(HttpHeaders.REFERER, config.baseUrl() + "/anime/")
                        .defaultHeader("X-Requested-With", "XMLHttpRequest")
                        .build();
        this.pageClient =
                webClientBuilder
                        .clone()
                        .defaultHeader(HttpHeaders.USER_AGENT, config.userAgent())
                        .defaultHeader(
                                HttpHeaders.ACCEPT,
                                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "ru-RU,ru;q=0.9,en;q=0.8")
                        .build();
        this.rateLimiter = rateLimiter;
        this.sessionManager = sessionManager;
        this.driftDetector = driftDetector;
        this.baseUrl = config.baseUrl();
    }

    /** Fetch a single notice feed page at the supplied cursor. */
    public Mono<JutsuNoticeFeed> getFeed(int noticeId) {
        if (noticeId < 0) {
            return Mono.error(new IllegalArgumentException("noticeId must be ≥ 0: " + noticeId));
        }
        return rateLimiter
                .acquire()
                .then(sessionManager.cookieHeader().defaultIfEmpty(""))
                .flatMap(cookie -> performNoticeFetch(noticeId, cookie))
                .map(html -> parse(html, noticeId));
    }

    /**
     * Discover the freshest notice cursor by scraping the homepage, then fetch the corresponding
     * feed. The homepage embeds {@code show_top_notice( N )} where {@code N} is the cursor jut.su
     * would use to load the next 50 newer-than-current entries; we use {@code N + 1} so the
     * returned page includes the very latest notice currently on the chrome.
     */
    public Mono<JutsuNoticeFeed> getLatestFeed() {
        return discoverLatestCursor().flatMap(this::getFeed);
    }

    /**
     * Stream feeds page-by-page going backwards in history, starting at {@code startNoticeId}.
     * Stops when the history bound is reached (empty feed) or {@code maxFeeds} pages have been
     * emitted (whichever is sooner). {@code maxFeeds &lt;= 0} disables the cap.
     */
    public Flux<JutsuNoticeFeed> walkFeedsBackwards(int startNoticeId, int maxFeeds) {
        if (startNoticeId < 0) {
            return Flux.error(
                    new IllegalArgumentException("startNoticeId must be ≥ 0: " + startNoticeId));
        }
        long cap = maxFeeds <= 0 ? Long.MAX_VALUE : maxFeeds;
        return getFeed(startNoticeId)
                .expand(feed -> feed.nextCursor().map(this::getFeed).orElseGet(Mono::empty))
                .filter(JutsuNoticeFeed::hasEntries)
                .take(cap);
    }

    /**
     * Stream individual notice entries going backwards in history, starting at {@code
     * startNoticeId}. Equivalent to {@link #walkFeedsBackwards(int, int)} flat-mapped on entries.
     */
    public Flux<JutsuNoticeEntry> streamEntries(int startNoticeId, int maxFeeds) {
        return walkFeedsBackwards(startNoticeId, maxFeeds)
                .concatMapIterable(JutsuNoticeFeed::entries);
    }

    private Mono<Integer> discoverLatestCursor() {
        return rateLimiter
                .acquire()
                .then(sessionManager.cookieHeader().defaultIfEmpty(""))
                .flatMap(this::fetchHomepage)
                .map(this::parseLatestCursor);
    }

    private Mono<String> fetchHomepage(String cookieHeader) {
        JutsuParserContext httpCtx =
                JutsuParserContext.lenient(driftDetector, "JutsuNoticeClient.discoverLatest");
        return pageClient
                .get()
                .uri(baseUrl + "/")
                .headers(
                        h -> {
                            if (!cookieHeader.isEmpty()) {
                                h.add(HttpHeaders.COOKIE, cookieHeader);
                            }
                        })
                .exchangeToMono(
                        resp -> {
                            int status = resp.statusCode().value();
                            if (status != 200) {
                                httpCtx.observe(
                                        JutsuDriftSignal.UNEXPECTED_HTTP_STATUS,
                                        "homepage GET returned " + status);
                            }
                            MediaType ct = resp.headers().contentType().orElse(null);
                            return resp.bodyToMono(byte[].class)
                                    .defaultIfEmpty(new byte[0])
                                    .map(bytes -> JutsuHtmlCharset.decode(bytes, ct));
                        });
    }

    private Integer parseLatestCursor(String html) {
        JutsuParserContext ctx =
                JutsuParserContext.lenient(driftDetector, "JutsuNoticeClient.discoverLatest");
        Matcher m = LATEST_CURSOR_PATTERN.matcher(html);
        if (!m.find()) {
            ctx.observe(
                    JutsuDriftSignal.SCHEMA_VIOLATION,
                    "homepage doesn't expose show_top_notice(N) marker");
            throw new IllegalStateException("could not discover latest notice cursor");
        }
        int discovered = Integer.parseInt(m.group(1));
        // The homepage's marker is the cursor for "next previous batch"; the very latest notice
        // ID is one above it. Add 1 so the returned feed includes the live top notice.
        return discovered + 1;
    }

    private Mono<String> performNoticeFetch(int noticeId, String cookieHeader) {
        JutsuParserContext httpCtx = JutsuParserContext.lenient(driftDetector, "JutsuNoticeClient");
        String body = "action=show&notice_id=" + noticeId;
        return ajaxClient
                .post()
                .uri(baseUrl + NOTICE_PATH)
                .headers(
                        h -> {
                            h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
                            if (!cookieHeader.isEmpty()) {
                                h.add(HttpHeaders.COOKIE, cookieHeader);
                            }
                        })
                .body(BodyInserters.fromValue(body))
                .exchangeToMono(
                        resp -> {
                            int status = resp.statusCode().value();
                            if (status != 200) {
                                httpCtx.observe(
                                        JutsuDriftSignal.UNEXPECTED_HTTP_STATUS,
                                        "POST "
                                                + NOTICE_PATH
                                                + " (notice_id="
                                                + noticeId
                                                + ") returned "
                                                + status);
                            }
                            MediaType ct = resp.headers().contentType().orElse(null);
                            return resp.bodyToMono(byte[].class)
                                    .defaultIfEmpty(new byte[0])
                                    .map(bytes -> JutsuHtmlCharset.decode(bytes, ct));
                        });
    }

    private JutsuNoticeFeed parse(String html, int requestedCursor) {
        JutsuParserContext ctx = JutsuParserContext.lenient(driftDetector, "JutsuNoticeParser");
        return new JutsuNoticeParser(ctx).parse(html, requestedCursor);
    }
}
