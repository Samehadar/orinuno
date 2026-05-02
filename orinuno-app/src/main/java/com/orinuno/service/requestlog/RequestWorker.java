package com.orinuno.service.requestlog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.model.KodikContent;
import com.orinuno.model.OrinunoParseRequest;
import com.orinuno.model.ParseRequestStatus;
import com.orinuno.model.dto.ParseRequestDto;
import com.orinuno.repository.ParseRequestRepository;
import com.orinuno.service.ParserService;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Single-threaded scheduled worker that drains the parse-request queue. Each tick claims the oldest
 * PENDING row via FOR UPDATE SKIP LOCKED (delegated to {@link ParseRequestQueueService}), runs
 * {@link ParserService#searchInternal} synchronously (block) and writes the terminal status.
 *
 * <p>Blocking is intentional — the project runs a single worker instance, which keeps Kodik
 * request-rate predictable and lets us reuse MyBatis transactions without bridging reactor
 * schedulers. See TECH_DEBT TD-PR-1.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequestWorker {

    private final ParseRequestQueueService queueService;
    private final ParseRequestRepository repository;
    private final ParserService parserService;
    private final ObjectMapper objectMapper;
    private final OrinunoProperties properties;
    private final Clock clock;
    private final ParseRequestMetrics metrics;

    @Scheduled(fixedDelayString = "${orinuno.requests.worker-poll-ms:2000}")
    public void tick() {
        Timer.Sample sample = Timer.start();
        try {
            Optional<Long> claimed;
            try {
                claimed = queueService.claimNext();
            } catch (Exception e) {
                log.warn("⚠️ Worker failed to claim next pending request", e);
                return;
            }
            claimed.ifPresent(this::process);
        } finally {
            sample.stop(metrics.workerTickTimer());
        }
    }

    private void process(Long requestId) {
        OrinunoParseRequest claimed = repository.findById(requestId).orElse(null);
        if (claimed == null) {
            log.warn("⚠️ Claimed parse request id={} disappeared after claim", requestId);
            return;
        }

        ParseRequestDto dto;
        try {
            dto = objectMapper.readValue(claimed.getRequestJson(), ParseRequestDto.class);
        } catch (Exception e) {
            log.error("❌ Failed to deserialize request_json for id={}", requestId, e);
            long failStart = System.nanoTime();
            failRequest(requestId, claimed, "invalid request_json: " + e.getMessage());
            metrics.recordCompletion(ParseRequestStatus.FAILED, failStart);
            return;
        }

        Duration flush = Duration.ofMillis(properties.getRequests().getProgressFlushMs());
        ThrottledProgressReporter reporter =
                new ThrottledProgressReporter(repository, requestId, flush, clock);

        long startNanos = System.nanoTime();
        try {
            List<KodikContent> contents = parserService.searchInternal(dto, reporter).block();
            reporter.flush();

            String resultJson = "[]";
            if (contents != null && !contents.isEmpty()) {
                List<Long> ids =
                        contents.stream()
                                .map(KodikContent::getId)
                                .filter(Objects::nonNull)
                                .collect(Collectors.toList());
                resultJson = objectMapper.writeValueAsString(ids);
            }
            LocalDateTime finishedAt = nowLdt();
            repository.markDone(requestId, resultJson, finishedAt);
            metrics.recordCompletion(ParseRequestStatus.DONE, startNanos);
            log.info(
                    "✅ Parse request id={} finished, results={}",
                    requestId,
                    contents == null ? 0 : contents.size());
        } catch (Exception e) {
            log.error("❌ Parse request id={} failed", requestId, e);
            reporter.flush();
            failRequest(requestId, claimed, e.getClass().getSimpleName() + ": " + e.getMessage());
            metrics.recordCompletion(ParseRequestStatus.FAILED, startNanos);
        }
    }

    private void failRequest(Long requestId, OrinunoParseRequest claimed, String error) {
        int retryCount = (claimed.getRetryCount() == null ? 0 : claimed.getRetryCount());
        repository.markFailed(requestId, truncate(error, 1024), nowLdt(), retryCount);
    }

    private LocalDateTime nowLdt() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Recovers RUNNING rows whose heartbeat is older than configured threshold. */
    @Scheduled(fixedDelayString = "${orinuno.requests.stale-recovery-ms:60000}")
    public void recoverStale() {
        LocalDateTime threshold =
                LocalDateTime.ofInstant(
                        clock.instant()
                                .minus(
                                        Duration.ofMillis(
                                                properties.getRequests().getStaleAfterMs())),
                        ZoneId.systemDefault());
        try {
            int recovered =
                    repository.recoverStale(threshold, properties.getRequests().getMaxRetries());
            if (recovered > 0) {
                log.warn("⚠️ Recovered {} stale RUNNING parse requests", recovered);
            }
        } catch (Exception e) {
            log.warn("⚠️ recoverStale failed", e);
        }
    }
}
