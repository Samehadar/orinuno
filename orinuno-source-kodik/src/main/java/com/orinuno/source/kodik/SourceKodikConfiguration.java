/*
 * SourceKodikConfiguration — module-wide Spring wiring for orinuno-source-kodik.
 *
 * The kodik-sdk-spring-boot-starter auto-config provides the SDK beans (HTTP client,
 * token registry, decoder metrics, etc.); this class fills in the few infrastructure
 * beans that the SDK auto-config deliberately leaves to the host — currently just a
 * Clock.systemUTC() bean consumed by KodikSourceEventProjection for the SourceCatalogEvent
 * Provenance.fetchedAt timestamp.
 */
package com.orinuno.source.kodik;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SourceKodikConfiguration {

    /**
     * UTC system clock. Provided as a Spring bean so {@link
     * com.orinuno.source.kodik.service.KodikSourceEventProjection} stays testable (tests can
     * override with a fixed-clock {@code @TestConfiguration}).
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
