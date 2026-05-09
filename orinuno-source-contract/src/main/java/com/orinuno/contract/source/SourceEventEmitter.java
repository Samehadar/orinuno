package com.orinuno.contract.source;

/**
 * Single-method sink that source bounded contexts call after each L1 upsert. Implementations decide
 * whether to deliver synchronously into an in-process catalog (the default {@code
 * CatalogSinkEventEmitter} inside {@code orinuno-app}), enqueue into an outbox for later remote
 * delivery, or post directly to a remote consumer (Kin's {@code external-bridge}, future OSS
 * aggregator).
 *
 * <p>The contract is deliberately thin and synchronous: emitting is always called inside the same
 * transaction that performed the L1 upsert, so failures must propagate as exceptions if the
 * implementation cannot guarantee at-least-once semantics for that event. The default in-process
 * emitter swallows exceptions internally to keep L1 writes independent of L3 hiccups (see ADR 0017
 * §"What does NOT change") — async outbox-backed implementations must NOT swallow.
 *
 * <p>This is a pure functional interface: implementations may be {@code @Component}-annotated,
 * registered manually, or wrapped in a Spring {@code @Async} executor at the consumer's discretion.
 * The contract artifact does not depend on Spring.
 */
@FunctionalInterface
public interface SourceEventEmitter {

    /** Deliver one event. Implementations decide synchronicity, retries, and durability. */
    void emit(SourceCatalogEvent event);
}
