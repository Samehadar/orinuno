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

    private final KodikTokenValidator validator;
    private final OrinunoProperties properties;

    public KodikTokenLifecycle(KodikTokenValidator validator, OrinunoProperties properties) {
        this.validator = validator;
        this.properties = properties;
    }

    @PostConstruct
    public void onStart() {
        if (!properties.getKodik().isValidateOnStartup()) {
            log.info("Kodik token startup validation disabled by config");
            return;
        }
        if (!validator.hasAnythingToValidate()) {
            log.info(
                    "Kodik token startup validation skipped — registry is empty (no live tokens"
                            + " and no cooldown-eligible DEAD candidates)");
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
        if (!validator.hasAnythingToValidate()) {
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
}
