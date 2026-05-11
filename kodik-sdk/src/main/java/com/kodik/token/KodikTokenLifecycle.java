package com.kodik.token;

import lombok.extern.slf4j.Slf4j;

/**
 * Runs the token validator on application start (so the registry's availability matrix is fresh
 * before the first API call) and on every scheduled revalidation tick. Off-loads blocking HTTP work
 * to a separate thread so the startup never blocks the host process.
 *
 * <p>ADR 0018 Phase 1.4b — Spring-free. orinuno-app's KodikSdkConfiguration calls {@link
 * #onStart()} from its own {@code @PostConstruct} and registers a {@code @Scheduled} wrapper that
 * forwards to {@link #scheduledRevalidation()}.
 */
@Slf4j
public class KodikTokenLifecycle {

    private final KodikTokenValidator validator;
    private final KodikTokenConfig config;

    public KodikTokenLifecycle(KodikTokenValidator validator, KodikTokenConfig config) {
        this.validator = validator;
        this.config = config;
    }

    public void onStart() {
        if (!config.validateOnStartup()) {
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
