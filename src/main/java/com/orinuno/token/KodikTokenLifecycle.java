package com.orinuno.token;

import com.orinuno.configuration.OrinunoProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the token validator on application start (so the registry's availability matrix is fresh
 * before the first API call) and every {@code orinuno.kodik.validation-interval-minutes} after
 * that. Off-loads blocking HTTP work to a separate thread so the startup never blocks the Spring
 * context.
 */
@Slf4j
@Component
public class KodikTokenLifecycle {

    private final KodikTokenRegistry registry;
    private final KodikTokenValidator validator;
    private final OrinunoProperties properties;

    public KodikTokenLifecycle(
            KodikTokenRegistry registry,
            KodikTokenValidator validator,
            OrinunoProperties properties) {
        this.registry = registry;
        this.validator = validator;
        this.properties = properties;
    }

    @PostConstruct
    public void onStart() {
        if (!properties.getKodik().isValidateOnStartup()) {
            log.info("Kodik token startup validation disabled by config");
            return;
        }
        if (totalLive() == 0) {
            log.info("Kodik token startup validation skipped — registry is empty");
            return;
        }
        Thread worker =
                new Thread(
                        () -> {
                            try {
                                log.info("Kodik token startup validation starting");
                                validator.validateAll();
                                log.info("Kodik token startup validation finished");
                            } catch (RuntimeException ex) {
                                log.warn(
                                        "Kodik token startup validation failed: {}",
                                        ex.getMessage());
                            }
                        },
                        "kodik-token-bootstrap");
        worker.setDaemon(true);
        worker.start();
    }

    @Scheduled(
            fixedRateString = "${orinuno.kodik.validation-interval-minutes:360}",
            timeUnit = java.util.concurrent.TimeUnit.MINUTES,
            initialDelayString = "${orinuno.kodik.validation-interval-minutes:360}")
    public void scheduledRevalidation() {
        if (totalLive() == 0) {
            return;
        }
        try {
            log.info("Kodik token scheduled revalidation starting");
            validator.validateAll();
            log.info("Kodik token scheduled revalidation finished");
        } catch (RuntimeException ex) {
            log.warn("Kodik token scheduled revalidation failed: {}", ex.getMessage());
        }
    }

    private int totalLive() {
        return registry.countFor(KodikTokenTier.STABLE)
                + registry.countFor(KodikTokenTier.UNSTABLE)
                + registry.countFor(KodikTokenTier.LEGACY);
    }
}
