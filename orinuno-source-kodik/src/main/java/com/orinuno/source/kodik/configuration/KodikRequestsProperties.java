/*
 * KodikRequestsProperties — ADR 0021 §D1c (partial Block E2).
 *
 * Parse-request queue tuning knobs. Replaces the legacy
 * OrinunoProperties.RequestsProperties subtree for source-kodik.
 * Property prefix: orinuno.source-kodik.requests.*. Defaults preserve
 * legacy orinuno-app values.
 */
package com.orinuno.source.kodik.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "orinuno.source-kodik.requests")
public class KodikRequestsProperties {

    private int defaultPageLimit = 50;
    private int maxPageLimit = 200;
    private long workerPollMs = 2_000;
    private long staleAfterMs = 300_000;
    private long progressFlushMs = 1_000;
    private int maxRetries = 3;
}
