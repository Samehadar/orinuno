package com.orinuno.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DecoderHealthTracker {

    private final Counter successCounter;
    private final Counter failureCounter;

    @Getter private final AtomicLong totalDecoded = new AtomicLong(0);
    @Getter private final AtomicLong totalFailed = new AtomicLong(0);
    @Getter private final AtomicReference<String> lastFailureStep = new AtomicReference<>(null);
    @Getter private final AtomicReference<String> lastFailureMessage = new AtomicReference<>(null);
    @Getter private final AtomicReference<Instant> lastFailureTime = new AtomicReference<>(null);

    public DecoderHealthTracker(MeterRegistry meterRegistry) {
        this.successCounter =
                Counter.builder("kodik.decoder.success")
                        .description("Successful decode operations")
                        .register(meterRegistry);
        this.failureCounter =
                Counter.builder("kodik.decoder.failure")
                        .description("Failed decode operations")
                        .register(meterRegistry);
    }

    public void recordSuccess() {
        totalDecoded.incrementAndGet();
        successCounter.increment();
    }

    public void recordFailure(String step, String message) {
        totalFailed.incrementAndGet();
        failureCounter.increment();
        lastFailureStep.set(step);
        lastFailureMessage.set(message);
        lastFailureTime.set(Instant.now());
    }

    public double getSuccessRate() {
        long total = totalDecoded.get() + totalFailed.get();
        if (total == 0) return 1.0;
        return (double) totalDecoded.get() / total;
    }
}
