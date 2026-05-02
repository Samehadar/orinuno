package com.orinuno.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import com.orinuno.client.http.RotatingUserAgentProvider;
import com.orinuno.configuration.OrinunoProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Fetches video content via headless Chromium browser (Playwright). Navigates to Kodik player page,
 * intercepts network requests to video files, and captures the response body — bypassing CDN
 * anti-bot protections.
 */
@Slf4j
@Service
public class PlaywrightVideoFetcher {

    private final OrinunoProperties properties;
    private final RotatingUserAgentProvider userAgentProvider;

    private Playwright playwright;
    private Browser browser;
    private final AtomicReference<State> state = new AtomicReference<>(State.NOT_INITIALIZED);
    private final java.util.concurrent.Semaphore browserSemaphore =
            new java.util.concurrent.Semaphore(1);

    enum State {
        NOT_INITIALIZED,
        READY,
        FAILED,
        CLOSED
    }

    public PlaywrightVideoFetcher(
            OrinunoProperties properties, RotatingUserAgentProvider userAgentProvider) {
        this.properties = properties;
        this.userAgentProvider = userAgentProvider;
    }

    @PostConstruct
    void init() {
        if (!properties.getPlaywright().isEnabled()) {
            log.info("Playwright is disabled via configuration");
            state.set(State.CLOSED);
            return;
        }
        try {
            playwright = Playwright.create();
            browser =
                    playwright
                            .chromium()
                            .launch(
                                    new BrowserType.LaunchOptions()
                                            .setHeadless(properties.getPlaywright().isHeadless())
                                            .setArgs(
                                                    java.util.List.of(
                                                            "--disable-blink-features=AutomationControlled",
                                                            "--no-sandbox",
                                                            "--disable-dev-shm-usage")));
            state.set(State.READY);
            log.info(
                    "Playwright browser initialized (headless={})",
                    properties.getPlaywright().isHeadless());
        } catch (Exception e) {
            log.error("Failed to initialize Playwright: {}", e.getMessage(), e);
            state.set(State.FAILED);
        }
    }

    @PreDestroy
    void destroy() {
        state.set(State.CLOSED);
        if (browser != null) {
            try {
                browser.close();
            } catch (Exception ignored) {
            }
        }
        if (playwright != null) {
            try {
                playwright.close();
            } catch (Exception ignored) {
            }
        }
        log.info("Playwright resources released");
    }

    public boolean isAvailable() {
        return state.get() == State.READY && browser != null;
    }

    /**
     * Creates a fresh BrowserContext with realistic UA/viewport and a minimal stealth init script
     * that hides the most common headless-detection signals (navigator.webdriver, empty plugins,
     * missing window.chrome, unusual languages list). Geo-blocking based on IP is NOT addressed
     * here — use proxy rotation for that.
     */
    private BrowserContext newStealthContext() {
        BrowserContext context =
                browser.newContext(
                        new Browser.NewContextOptions()
                                .setUserAgent(userAgentProvider.stableDesktop())
                                .setViewportSize(1280, 720)
                                .setLocale("en-US")
                                .setTimezoneId("Europe/London"));
        context.addInitScript(STEALTH_INIT_SCRIPT);
        return context;
    }

    private static final String STEALTH_INIT_SCRIPT =
            "Object.defineProperty(navigator, 'webdriver', { get: () => undefined });"
                    + "Object.defineProperty(navigator, 'languages', { get: () => ['en-US','en']"
                    + " });"
                    + "Object.defineProperty(navigator, 'plugins', { get: () => [1,2,3,4,5] });"
                    + "window.chrome = window.chrome || { runtime: {} };"
                    + "const origQuery = window.navigator.permissions &&"
                    + " window.navigator.permissions.query;"
                    + "if (origQuery) {"
                    + "  window.navigator.permissions.query = (p) =>"
                    + "    p && p.name === 'notifications'"
                    + "      ? Promise.resolve({ state: Notification.permission })"
                    + "      : origQuery(p);"
                    + "}";

    /**
     * Downloads video from Kodik player page using headless browser. Intercepts .mp4 / .ts network
     * responses and saves to target file.
     *
     * @param kodikLink player link (e.g. //kodikplayer.com/seria/...)
     * @param targetPath where to save the video file
     * @param progress optional progress tracker updated during HLS segment download
     * @return Mono with absolute path of saved file, or error if failed
     */
    public Mono<String> downloadVideo(
            String kodikLink, Path targetPath, VideoDownloadService.DownloadProgress progress) {
        if (!isAvailable()) {
            return Mono.error(new IllegalStateException("Playwright browser is not available"));
        }

        String fullUrl = normalizeUrl(kodikLink);

        return Mono.fromCallable(() -> fetchVideoBlocking(fullUrl, targetPath, progress))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<String> downloadVideo(String kodikLink, Path targetPath) {
        return downloadVideo(kodikLink, targetPath, null);
    }

    /**
     * Fetches the direct video URL (after CDN redirects) from the Kodik player page. Useful for
     * streaming proxy — we get the final URL with valid cookies/headers.
     *
     * @param kodikLink player link
     * @return Mono with the intercepted video URL
     */
    public Mono<InterceptedVideo> interceptVideoUrl(String kodikLink) {
        if (!isAvailable()) {
            return Mono.error(new IllegalStateException("Playwright browser is not available"));
        }

        String fullUrl = normalizeUrl(kodikLink);

        return Mono.fromCallable(() -> interceptBlocking(fullUrl))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public record InterceptedVideo(String url, String contentType, long contentLength) {}

    /**
     * Strategy: navigate to the Kodik player, click play, wait for the browser to make the video
     * request, then use page.evaluate(fetch()) to download the video FROM the browser context with
     * all cookies/headers intact.
     */
    private String fetchVideoBlocking(
            String kodikUrl, Path targetPath, VideoDownloadService.DownloadProgress progress)
            throws Exception {
        browserSemaphore.acquire();
        try {
            return doFetchVideoBlocking(kodikUrl, targetPath, progress);
        } finally {
            browserSemaphore.release();
        }
    }

    private String doFetchVideoBlocking(
            String kodikUrl, Path targetPath, VideoDownloadService.DownloadProgress progress)
            throws Exception {
        var pwProps = properties.getPlaywright();
        Path targetDir = targetPath.getParent();
        if (targetDir == null) {
            throw new IllegalArgumentException("targetPath must live inside a directory");
        }
        Files.createDirectories(targetDir);

        BrowserContext context = newStealthContext();

        try {
            Page page = context.newPage();
            page.setDefaultTimeout(pwProps.getPageTimeoutSeconds() * 1000.0);

            LinkedBlockingQueue<String> candidateUrls = new LinkedBlockingQueue<>();

            page.onResponse(
                    response -> {
                        String url = response.url();
                        if (url.contains("solodcdn.com/s/m/")
                                || (url.contains("solodcdn.com/s/") && url.contains(".mp4"))) {
                            log.debug(
                                    "CDN candidate URL queued: {}",
                                    url.substring(0, Math.min(100, url.length())));
                            candidateUrls.offer(url);
                        }
                    });

            log.info("Navigating to Kodik player: {}", kodikUrl);
            page.navigate(
                    kodikUrl,
                    new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(pwProps.getNavigationTimeoutMs()));

            triggerPlayback(page);

            long deadline = System.currentTimeMillis() + pwProps.getVideoWaitMs();
            APIRequestContext apiRequest = context.request();

            while (System.currentTimeMillis() < deadline) {
                page.waitForTimeout(1000);

                String candidateUrl;
                while ((candidateUrl = candidateUrls.poll()) != null) {
                    log.info(
                            "Trying candidate URL: {}...",
                            candidateUrl.substring(0, Math.min(80, candidateUrl.length())));

                    byte[] responseBytes = null;
                    String finalUrl = null;
                    String ct = null;
                    for (int attempt = 1; attempt <= 3; attempt++) {
                        APIResponse apiResponse = null;
                        try {
                            apiResponse = apiRequest.get(candidateUrl);
                            log.info(
                                    "API response: status={}, url={}",
                                    apiResponse.status(),
                                    apiResponse
                                            .url()
                                            .substring(
                                                    0, Math.min(80, apiResponse.url().length())));
                            if (!apiResponse.ok()) {
                                log.warn(
                                        "Candidate URL returned HTTP {}, skipping",
                                        apiResponse.status());
                                apiResponse.dispose();
                                responseBytes = null;
                                break;
                            }
                            responseBytes = apiResponse.body();
                            finalUrl = apiResponse.url();
                            ct = apiResponse.headers().get("content-type");
                            apiResponse.dispose();
                            break;
                        } catch (Exception e) {
                            if (apiResponse != null) {
                                try {
                                    apiResponse.dispose();
                                } catch (Exception ignored) {
                                }
                            }
                            if (attempt == 3) {
                                log.warn(
                                        "Candidate GET failed after {} attempts: {}",
                                        attempt,
                                        e.getMessage());
                                responseBytes = null;
                                break;
                            }
                            log.warn(
                                    "Candidate GET attempt {} failed ({}), retrying...",
                                    attempt,
                                    e.getMessage());
                            Thread.sleep(300L * attempt);
                        }
                    }
                    if (responseBytes == null) continue;

                    if (ct != null && ct.startsWith("image/")) {
                        log.info("Skipping poster image (content-type={})", ct);
                        continue;
                    }

                    if (isHlsManifest(responseBytes)) {
                        log.info(
                                "CDN returned HLS manifest ({} bytes), downloading segments...",
                                responseBytes.length);
                        return downloadHlsSegments(
                                context, finalUrl, responseBytes, targetPath, progress);
                    }

                    long size = responseBytes.length;
                    if (size < 100_000) {
                        log.info("Skipping small response ({} bytes), likely not a video", size);
                        continue;
                    }

                    try (OutputStream os =
                            Files.newOutputStream(
                                    targetPath,
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.WRITE,
                                    StandardOpenOption.TRUNCATE_EXISTING)) {
                        os.write(responseBytes);
                    }

                    log.info(
                            "Saved video via Playwright: {} bytes ({} MB) to {}",
                            size,
                            size / (1024 * 1024),
                            targetPath);
                    return targetPath.toAbsolutePath().toString();
                }
            }

            throw new RuntimeException(
                    "Timeout waiting for video URL from player ("
                            + pwProps.getVideoWaitMs()
                            + "ms)");
        } finally {
            context.close();
        }
    }

    private InterceptedVideo interceptBlocking(String kodikUrl) throws Exception {
        browserSemaphore.acquire();
        try {
            return doInterceptBlocking(kodikUrl);
        } finally {
            browserSemaphore.release();
        }
    }

    private InterceptedVideo doInterceptBlocking(String kodikUrl) throws Exception {
        var pwProps = properties.getPlaywright();

        BrowserContext context = newStealthContext();

        try {
            Page page = context.newPage();
            page.setDefaultTimeout(pwProps.getPageTimeoutSeconds() * 1000.0);

            LinkedBlockingQueue<String> candidateUrls = new LinkedBlockingQueue<>();

            page.onResponse(
                    response -> {
                        String url = response.url();
                        if (url.contains("solodcdn.com/s/m/")
                                || (url.contains("solodcdn.com/s/") && url.contains(".mp4"))) {
                            candidateUrls.offer(url);
                        }
                    });

            log.info("Navigating to Kodik player for URL interception: {}", kodikUrl);
            page.navigate(
                    kodikUrl,
                    new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(pwProps.getNavigationTimeoutMs()));

            triggerPlayback(page);

            long deadline = System.currentTimeMillis() + pwProps.getVideoWaitMs();
            APIRequestContext apiRequest = context.request();

            while (System.currentTimeMillis() < deadline) {
                page.waitForTimeout(1000);

                String candidateUrl;
                while ((candidateUrl = candidateUrls.poll()) != null) {
                    APIResponse headResp = apiRequest.head(candidateUrl);
                    String contentType = headResp.headers().get("content-type");
                    String contentLengthStr = headResp.headers().get("content-length");
                    long contentLength =
                            contentLengthStr != null ? parseLongSafe(contentLengthStr) : -1;
                    headResp.dispose();

                    if (contentType != null && contentType.startsWith("image/")) {
                        log.info("Skipping poster image URL: type={}", contentType);
                        continue;
                    }

                    log.info(
                            "Intercepted video URL: {} (type={}, len={})",
                            candidateUrl.substring(0, Math.min(100, candidateUrl.length())),
                            contentType,
                            contentLength);
                    return new InterceptedVideo(candidateUrl, contentType, contentLength);
                }
            }

            throw new RuntimeException(
                    "Timeout waiting for video request from player ("
                            + pwProps.getVideoWaitMs()
                            + "ms)");
        } finally {
            context.close();
        }
    }

    private void triggerPlayback(Page page) {
        page.waitForTimeout(2000);

        // The Kodik player page itself IS the player (not embedded in an iframe from our
        // perspective).
        // It uses a custom JS player with a play button overlay.
        try {
            page.evaluate(
                    """
                    (() => {
                        // Try clicking any play button
                        const selectors = [
                            '.play_button', '.fp-play', '.vjs-big-play-button',
                            '[class*="play"]', '.kodik-player-play', 'button[aria-label="Play"]',
                            '.center-play-button', '.player__play'
                        ];
                        for (const sel of selectors) {
                            const el = document.querySelector(sel);
                            if (el) { el.click(); break; }
                        }
                        // Also try direct video element
                        const video = document.querySelector('video');
                        if (video) {
                            video.muted = true;
                            video.play().catch(() => {});
                        }
                    })()
                    """);
            log.debug("Triggered video playback via JS (attempt 1)");
        } catch (Exception e) {
            log.debug("JS play trigger failed: {}", e.getMessage());
        }

        // Click in the center of the page (common for video players)
        try {
            page.click("body", new Page.ClickOptions().setPosition(640, 360).setTimeout(2000));
            log.debug("Clicked center of page");
        } catch (Exception e) {
            log.debug("Center-of-page click failed (continuing): {}", e.getMessage());
        }

        page.waitForTimeout(1000);

        // Second attempt after a short delay
        try {
            page.evaluate(
                    """
                    (() => {
                        const video = document.querySelector('video');
                        if (video && video.paused) {
                            video.muted = true;
                            video.play().catch(() => {});
                        }
                    })()
                    """);
        } catch (Exception ignored) {
        }
    }

    private static boolean isHlsManifest(byte[] data) {
        if (data == null || data.length < 7) return false;
        String header =
                new String(
                        data,
                        0,
                        Math.min(data.length, 50),
                        java.nio.charset.StandardCharsets.UTF_8);
        return header.startsWith("#EXTM3U");
    }

    private static byte[] downloadHlsSegmentBytes(
            HttpClient httpClient, HttpRequest request, int oneBasedIdx, int totalSegments) {
        final int maxAttempts = 4;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<byte[]> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    byte[] body = response.body();
                    return body != null ? body : new byte[0];
                }
                log.warn(
                        "Segment {}/{} HTTP {}", oneBasedIdx, totalSegments, response.statusCode());
                return new byte[0];
            } catch (IOException e) {
                log.warn(
                        "Segment {}/{} I/O attempt {}: {}",
                        oneBasedIdx,
                        totalSegments,
                        attempt,
                        e.getMessage());
                if (attempt == maxAttempts) {
                    return new byte[0];
                }
                try {
                    Thread.sleep(250L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return new byte[0];
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new byte[0];
            }
        }
        return new byte[0];
    }

    /**
     * Downloads .ts segments from HLS manifest in parallel using Java HttpClient.
     *
     * <p>Playwright's APIRequestContext is NOT thread-safe (shares a single WebSocket), so we
     * extract cookies from the BrowserContext and use java.net.http.HttpClient which handles
     * concurrent requests natively.
     */
    private String downloadHlsSegments(
            BrowserContext context,
            String manifestUrl,
            byte[] manifestBytes,
            Path targetPath,
            VideoDownloadService.DownloadProgress progress)
            throws Exception {
        String manifest = new String(manifestBytes, java.nio.charset.StandardCharsets.UTF_8);
        String baseUrl = manifestUrl.substring(0, manifestUrl.lastIndexOf('/') + 1);

        java.util.List<String> segmentUrls = new java.util.ArrayList<>();
        for (String line : manifest.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("./")) {
                segmentUrls.add(baseUrl + line.substring(2));
            } else if (line.startsWith("http")) {
                segmentUrls.add(line);
            } else {
                segmentUrls.add(baseUrl + line);
            }
        }

        int concurrency = properties.getPlaywright().getHlsConcurrency();
        log.info(
                "HLS manifest contains {} segments, downloading with concurrency={}...",
                segmentUrls.size(),
                concurrency);

        if (progress != null) {
            progress.setTotalSegments(segmentUrls.size());
        }

        String targetStr = targetPath.toString();
        if (targetStr.endsWith(".mp4")) {
            targetPath = Path.of(targetStr.replace(".mp4", ".ts"));
        }

        CookieManager cookieManager = new CookieManager();
        for (var cookie : context.cookies()) {
            HttpCookie httpCookie = new HttpCookie(cookie.name, cookie.value);
            httpCookie.setDomain(cookie.domain);
            httpCookie.setPath(cookie.path);
            httpCookie.setSecure(cookie.secure);
            httpCookie.setHttpOnly(cookie.httpOnly);
            try {
                String scheme = cookie.secure ? "https" : "http";
                cookieManager
                        .getCookieStore()
                        .add(
                                URI.create(scheme + "://" + cookie.domain.replaceFirst("^\\.", "")),
                                httpCookie);
            } catch (Exception e) {
                log.debug("Skipped cookie {}: {}", cookie.name, e.getMessage());
            }
        }
        log.debug("Extracted {} cookies from browser context", context.cookies().size());

        HttpClient httpClient =
                HttpClient.newBuilder()
                        .cookieHandler(cookieManager)
                        .connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();

        int totalSegments = segmentUrls.size();
        byte[][] segmentData = new byte[totalSegments][];
        AtomicInteger completedCount = new AtomicInteger(0);
        AtomicInteger failedCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);

        try {
            Semaphore semaphore = new Semaphore(concurrency);
            CompletableFuture<?>[] futures = new CompletableFuture[totalSegments];

            for (int i = 0; i < totalSegments; i++) {
                final int idx = i;
                final String segUrl = segmentUrls.get(i);

                futures[i] =
                        CompletableFuture.runAsync(
                                () -> {
                                    try {
                                        semaphore.acquire();
                                        try {
                                            HttpRequest request =
                                                    HttpRequest.newBuilder()
                                                            .uri(URI.create(segUrl))
                                                            .header(
                                                                    "User-Agent",
                                                                    userAgentProvider
                                                                            .stableDesktop())
                                                            .header("Referer", manifestUrl)
                                                            .timeout(Duration.ofSeconds(45))
                                                            .GET()
                                                            .build();

                                            byte[] body =
                                                    downloadHlsSegmentBytes(
                                                            httpClient,
                                                            request,
                                                            idx + 1,
                                                            totalSegments);
                                            if (body.length > 0) {
                                                segmentData[idx] = body;
                                                if (progress != null) {
                                                    progress.incrementDownloaded();
                                                    progress.addBytes(body.length);
                                                }
                                                log.debug(
                                                        "Segment {}/{} OK: {} bytes",
                                                        idx + 1,
                                                        totalSegments,
                                                        body.length);
                                            } else {
                                                log.warn(
                                                        "Segment {}/{} FAILED: empty body",
                                                        idx + 1,
                                                        totalSegments);
                                                segmentData[idx] = new byte[0];
                                                failedCount.incrementAndGet();
                                            }
                                        } finally {
                                            semaphore.release();
                                        }

                                        int done = completedCount.incrementAndGet();
                                        if (done % 50 == 0 || done == totalSegments) {
                                            log.info(
                                                    "HLS progress: {}/{} segments downloaded ({}"
                                                            + " failed)",
                                                    done,
                                                    totalSegments,
                                                    failedCount.get());
                                        }
                                    } catch (Exception e) {
                                        log.warn(
                                                "Segment {}/{} ERROR: {}",
                                                idx + 1,
                                                totalSegments,
                                                e.getMessage());
                                        segmentData[idx] = new byte[0];
                                        failedCount.incrementAndGet();
                                        completedCount.incrementAndGet();
                                    }
                                },
                                executor);
            }

            CompletableFuture.allOf(futures).get(10, TimeUnit.MINUTES);
        } finally {
            executor.shutdown();
        }

        long totalBytes = 0;
        try (OutputStream os =
                Files.newOutputStream(
                        targetPath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
            for (int i = 0; i < totalSegments; i++) {
                if (segmentData[i] != null && segmentData[i].length > 0) {
                    os.write(segmentData[i]);
                    totalBytes += segmentData[i].length;
                    segmentData[i] = null;
                }
            }
        }

        log.info(
                "HLS download complete: {} segments ({} failed), {} bytes ({} MB) to {}",
                totalSegments,
                failedCount.get(),
                totalBytes,
                totalBytes / (1024 * 1024),
                targetPath);

        if (totalBytes == 0) {
            Files.deleteIfExists(targetPath);
            throw new RuntimeException("All HLS segments empty (0 bytes total)");
        }

        Path mp4Path = remuxToMp4(targetPath);
        return mp4Path.toAbsolutePath().toString();
    }

    /**
     * Remux .ts to .mp4 using ffmpeg (stream copy, no re-encoding). Browsers can't play raw MPEG-TS
     * in a &lt;video&gt; element, but MP4 works everywhere.
     */
    private Path remuxToMp4(Path tsPath) throws Exception {
        String tsStr = tsPath.toString();
        Path mp4Path =
                Path.of(tsStr.endsWith(".ts") ? tsStr.replace(".ts", ".mp4") : tsStr + ".mp4");

        ProcessBuilder pb =
                new ProcessBuilder(
                        "ffmpeg",
                        "-y",
                        "-i",
                        tsPath.toAbsolutePath().toString(),
                        "-c",
                        "copy",
                        "-movflags",
                        "+faststart",
                        mp4Path.toAbsolutePath().toString());
        pb.redirectErrorStream(true);

        log.info("Remuxing TS -> MP4: {}", mp4Path.getFileName());
        Process process = pb.start();

        try (var reader =
                new java.io.BufferedReader(
                        new java.io.InputStreamReader(
                                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("ffmpeg: {}", line);
            }
        }

        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            log.warn("ffmpeg timed out, keeping .ts file");
            return tsPath;
        }

        if (process.exitValue() == 0 && Files.exists(mp4Path) && Files.size(mp4Path) > 0) {
            Files.deleteIfExists(tsPath);
            log.info(
                    "Remux complete: {} ({} MB)",
                    mp4Path.getFileName(),
                    Files.size(mp4Path) / (1024 * 1024));
            return mp4Path;
        }

        log.warn("ffmpeg failed (exit={}), keeping .ts file", process.exitValue());
        return tsPath;
    }

    private static boolean isVideoResponse(String url, String contentType, long contentLength) {
        boolean isVideoType =
                contentType != null
                        && (contentType.startsWith("video/")
                                || contentType.equals("application/octet-stream"));
        boolean isVideoUrl =
                url.contains(".mp4")
                        || url.contains(".ts")
                        || url.contains("solodcdn")
                        || url.contains("/s/m/");

        if (isVideoType && contentLength > 1000) {
            return true;
        }
        if (isVideoUrl && contentLength > 1000) {
            return true;
        }
        return false;
    }

    private static long parseLongSafe(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String normalizeUrl(String kodikLink) {
        if (kodikLink.startsWith("//")) {
            return "https:" + kodikLink;
        }
        if (!kodikLink.startsWith("http")) {
            return "https://" + kodikLink;
        }
        return kodikLink;
    }
}
