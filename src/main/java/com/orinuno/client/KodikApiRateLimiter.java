package com.orinuno.client;

import com.orinuno.configuration.OrinunoProperties;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class KodikApiRateLimiter {

    private final Semaphore semaphore;
    private final int maxPermits;
    private final ScheduledExecutorService scheduler;

    public KodikApiRateLimiter(OrinunoProperties properties) {
        this.maxPermits = properties.getParse().getRateLimitPerMinute();
        this.semaphore = new Semaphore(maxPermits);
        this.scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "kodik-rate-limiter");
                            t.setDaemon(true);
                            return t;
                        });
        this.scheduler.scheduleAtFixedRate(this::refill, 60, 60, TimeUnit.SECONDS);
        log.info("Kodik API rate limiter initialized: {} requests/minute", maxPermits);
    }

    public <T> Mono<T> wrapWithRateLimit(Mono<T> apiCall) {
        return Mono.defer(
                () -> {
                    if (semaphore.tryAcquire()) {
                        return apiCall;
                    }
                    log.warn(
                            "Kodik API rate limit reached ({}/min), waiting for refill...",
                            maxPermits);
                    return Mono.delay(Duration.ofSeconds(2))
                            .then(
                                    Mono.defer(
                                            () -> {
                                                try {
                                                    if (semaphore.tryAcquire(
                                                            30, TimeUnit.SECONDS)) {
                                                        return apiCall;
                                                    }
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                }
                                                return Mono.error(
                                                        new RuntimeException(
                                                                "Kodik API rate limit exceeded, try"
                                                                        + " again later"));
                                            }));
                });
    }

    private void refill() {
        int current = semaphore.availablePermits();
        int toRelease = maxPermits - current;
        if (toRelease > 0) {
            semaphore.release(toRelease);
            log.debug("Rate limiter refilled: {} permits restored", toRelease);
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
    }
}
