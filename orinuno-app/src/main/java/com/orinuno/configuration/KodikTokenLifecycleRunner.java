/*
 * KodikTokenLifecycleRunner — owns the startup + scheduled revalidation hooks for
 * KodikTokenLifecycle. Lives outside KodikSdkConfiguration so the @PostConstruct +
 * @Scheduled calls do not create a self-referencing cycle on the @Configuration
 * class (Spring 6.2 rejects ctor / init-method dependencies on a same-class @Bean).
 */
package com.orinuno.configuration;

import com.kodik.token.KodikTokenLifecycle;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class KodikTokenLifecycleRunner {

    private final KodikTokenLifecycle lifecycle;

    /** Replaces the SDK's former {@code @PostConstruct} on KodikTokenLifecycle. */
    @PostConstruct
    public void onStart() {
        lifecycle.onStart();
    }

    /**
     * Replaces the SDK's former {@code @Scheduled} on KodikTokenLifecycle. The same {@code
     * orinuno.kodik.validation-interval-minutes} property drives the cadence — the property layout
     * did not change, only where the annotation lives.
     */
    @Scheduled(
            fixedRateString = "${orinuno.kodik.validation-interval-minutes:360}",
            timeUnit = java.util.concurrent.TimeUnit.MINUTES,
            initialDelayString = "${orinuno.kodik.validation-interval-minutes:360}")
    public void scheduledTokenRevalidation() {
        lifecycle.scheduledRevalidation();
    }
}
