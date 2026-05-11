/*
 * orinuno-source-jutsu — Application entry point (ADR 0019 Phase 4.1).
 *
 * Standalone Spring Boot deployable for the jut.su parsing path. Owns the
 * jutsu_* MySQL schema, serves /api/v1/sources/jutsu/*, /api/v1/sources/jutsu/stream,
 * /api/v1/providers/jutsu/stream + the canonical /api/v1/source-events/ready stream.
 *
 * Playwright live-fallback (JutsuLiveFallbackService + circuit breaker + negative
 * cache) co-locates here once Phase 4.7 moves it over from orinuno-app — bound
 * Chromium OOM blast to this JVM, not the gateway.
 */
package com.orinuno.source.jutsu;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan(basePackages = "com.orinuno.source.jutsu.repository")
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
