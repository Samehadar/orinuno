/**
 * Root package for the standalone Kodik SDK (ADR 0018 Phase 1).
 *
 * <p>The SDK is currently a skeleton — code moves in Phase 1.2 through 1.5:
 *
 * <ul>
 *   <li>{@code com.kodik.client} — HTTP client, rate limiter, response mapper, embed shortcut
 *       (moved from {@code com.orinuno.client} in Phase 1.2/1.5).
 *   <li>{@code com.kodik.decoder} — 8-step ROT13+Base64 decode pipeline (moved from {@code
 *       com.orinuno.service.KodikVideoDecoderService} in Phase 1.3).
 *   <li>{@code com.kodik.token} — token registry, validator, auto-discovery (moved from {@code
 *       com.orinuno.token} in Phase 1.4).
 *   <li>{@code com.kodik.drift} — schema-drift detector (absorbed from {@code kodik-sdk-drift} in
 *       Phase 1.8).
 * </ul>
 *
 * <p>The SDK is Spring-free: no {@code @Component}, no {@code @Service}, no auto-configuration.
 * orinuno-app reaches for {@code kodik-sdk-spring-boot-starter} (Phase 1.6) to wire SDK beans into
 * a Spring Boot context.
 */
package com.kodik;
