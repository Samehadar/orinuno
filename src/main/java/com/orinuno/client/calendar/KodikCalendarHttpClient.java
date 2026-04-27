package com.orinuno.client.calendar;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orinuno.client.dto.calendar.KodikCalendarEntryDto;
import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.drift.DriftDetector;
import com.orinuno.service.metrics.KodikCalendarMetrics;
import io.netty.channel.ChannelOption;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * Low-level fetcher for the public Kodik calendar dump (IDEA-AP-5). Owns three concerns that the
 * caching service deliberately delegates here: response size capping, conditional GET (ETag /
 * Last-Modified), and drift detection on the parsed array.
 *
 * <p>The client keeps the most recent successful payload in {@link #lastSuccess} so that {@code 304
 * Not Modified} replies can resolve to the cached body without another full parse. Concurrent
 * callers are safe: {@link AtomicReference#set} is total, and emitting a stale-but-valid result on
 * 304 is acceptable for a 5-minute TTL endpoint.
 */
@Slf4j
@Component
public class KodikCalendarHttpClient {

    private static final TypeReference<List<KodikCalendarEntryDto>> ENTRY_LIST_TYPE =
            new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> RAW_LIST_TYPE =
            new TypeReference<>() {};
    private static final String UA = "orinuno/1.0 (+https://github.com/orinuno) calendar-watcher";

    private final OrinunoProperties properties;
    private final ObjectMapper objectMapper;
    private final DriftDetector driftDetector;
    private final KodikCalendarMetrics metrics;
    private final WebClient webClient;
    private final AtomicReference<CalendarFetchResult> lastSuccess = new AtomicReference<>();

    public KodikCalendarHttpClient(
            OrinunoProperties properties,
            ObjectMapper objectMapper,
            DriftDetector driftDetector,
            KodikCalendarMetrics metrics) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.driftDetector = driftDetector;
        this.metrics = metrics;
        this.webClient = buildWebClient(properties);
    }

    /**
     * Fetch the calendar dump. Honours {@code orinuno.calendar.enabled}, sends conditional GET when
     * a previous {@code ETag} / {@code Last-Modified} is known, and falls back to the previously
     * cached payload on 304.
     */
    public Mono<CalendarFetchResult> fetch() {
        if (!properties.getCalendar().isEnabled()) {
            metrics.fetchDisabled();
            return Mono.error(
                    new IllegalStateException(
                            "Kodik calendar fetcher is disabled (orinuno.calendar.enabled=false)"));
        }
        CalendarFetchResult previous = lastSuccess.get();
        long timeoutSeconds = properties.getCalendar().getRequestTimeoutSeconds();

        WebClient.RequestHeadersSpec<?> request =
                webClient
                        .get()
                        .uri(properties.getCalendar().getUrl())
                        .header(HttpHeaders.ACCEPT, "application/json")
                        .header(HttpHeaders.USER_AGENT, UA);
        if (previous != null) {
            if (previous.etag() != null) {
                request = request.header(HttpHeaders.IF_NONE_MATCH, previous.etag());
            }
            if (previous.lastModified() != null) {
                request = request.header(HttpHeaders.IF_MODIFIED_SINCE, previous.lastModified());
            }
        }

        return request.exchangeToMono(
                        response -> {
                            int status = response.statusCode().value();
                            if (status == 304) {
                                metrics.fetchNotModified();
                                CalendarFetchResult cached = lastSuccess.get();
                                if (cached == null) {
                                    return Mono.error(
                                            new IllegalStateException(
                                                    "Upstream returned 304 but local cache is"
                                                            + " empty"));
                                }
                                return Mono.just(cached);
                            }
                            if (status / 100 != 2) {
                                metrics.fetchError();
                                return response.createException().flatMap(Mono::error);
                            }
                            String etag = response.headers().asHttpHeaders().getETag();
                            String lastModified =
                                    response.headers()
                                            .asHttpHeaders()
                                            .getFirst(HttpHeaders.LAST_MODIFIED);
                            return response.bodyToMono(byte[].class)
                                    .flatMap(bytes -> parseAndRecord(bytes, etag, lastModified));
                        })
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .doOnError(
                        WebClientResponseException.class,
                        e ->
                                log.warn(
                                        "Calendar fetch upstream error status={} body={}",
                                        e.getStatusCode(),
                                        e.getResponseBodyAsString()))
                .doOnError(
                        e -> {
                            if (!(e instanceof WebClientResponseException)) {
                                metrics.fetchError();
                                log.warn("Calendar fetch failed: {}", e.toString());
                            }
                        });
    }

    /** Returns the last known {@link CalendarFetchResult}, or {@code null} if never fetched. */
    public CalendarFetchResult lastSuccess() {
        return lastSuccess.get();
    }

    private Mono<CalendarFetchResult> parseAndRecord(
            byte[] body, String etag, String lastModified) {
        long limit = properties.getCalendar().getMaxResponseBytes();
        if (body.length > limit) {
            metrics.fetchError();
            return Mono.error(
                    new IllegalStateException(
                            "Calendar response exceeds max-response-bytes ("
                                    + body.length
                                    + " > "
                                    + limit
                                    + ")"));
        }
        try {
            List<KodikCalendarEntryDto> entries = objectMapper.readValue(body, ENTRY_LIST_TYPE);
            recordDrift(body);
            CalendarFetchResult result =
                    new CalendarFetchResult(Instant.now(), etag, lastModified, entries);
            lastSuccess.set(result);
            metrics.fetchSuccess(entries.size());
            log.info(
                    "Calendar fetched: entries={} etag={} lastModified={}",
                    entries.size(),
                    etag,
                    lastModified);
            return Mono.just(result);
        } catch (IOException e) {
            metrics.fetchError();
            log.warn("Calendar parse failed: {}", e.toString());
            return Mono.error(e);
        }
    }

    private void recordDrift(byte[] body) {
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(body, RAW_LIST_TYPE);
            int sample = driftDetector.sampleSize(raw.size());
            for (int i = 0; i < sample; i++) {
                Map<String, Object> entry = raw.get(i);
                if (entry == null) {
                    continue;
                }
                driftDetector.detect(entry, KodikCalendarEntryDto.class, "KodikCalendarEntryDto");
                Object animeRaw = entry.get("anime");
                if (animeRaw instanceof Map<?, ?> animeMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> coerced = (Map<String, Object>) animeMap;
                    driftDetector.detect(
                            coerced,
                            com.orinuno.client.dto.calendar.KodikCalendarAnimeDto.class,
                            "KodikCalendarAnimeDto");
                    Object imageRaw = coerced.get("image");
                    if (imageRaw instanceof Map<?, ?> imageMap) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> coercedImage = (Map<String, Object>) imageMap;
                        driftDetector.detect(
                                coercedImage,
                                com.orinuno.client.dto.calendar.KodikCalendarImageDto.class,
                                "KodikCalendarImageDto");
                    }
                }
            }
        } catch (IOException e) {
            log.debug(
                    "Calendar drift sampling skipped — could not re-parse body: {}", e.toString());
        }
    }

    private static WebClient buildWebClient(OrinunoProperties properties) {
        long timeoutMs = Math.max(1, properties.getCalendar().getRequestTimeoutSeconds()) * 1000L;
        int memoryCap =
                (int) Math.min(Integer.MAX_VALUE, properties.getCalendar().getMaxResponseBytes());
        HttpClient http =
                HttpClient.create()
                        .followRedirect(true)
                        .responseTimeout(Duration.ofMillis(timeoutMs))
                        .option(
                                ChannelOption.CONNECT_TIMEOUT_MILLIS,
                                (int) Math.min(timeoutMs, 10_000));
        ExchangeStrategies strategies =
                ExchangeStrategies.builder()
                        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(memoryCap))
                        .build();
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(http))
                .exchangeStrategies(strategies)
                .build();
    }

    /** Test-only escape hatch — never invoke from production code. */
    void resetState() {
        lastSuccess.set(null);
    }

    /** Test-only escape hatch — preload state for unit tests. */
    void seed(CalendarFetchResult seed) {
        lastSuccess.set(Objects.requireNonNull(seed));
    }
}
