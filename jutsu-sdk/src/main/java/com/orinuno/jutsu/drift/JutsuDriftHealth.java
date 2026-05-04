package com.orinuno.jutsu.drift;

/**
 * Coarse health verdict computed by {@link JutsuDriftDetector#snapshot()} from the recent-events
 * window. Maps directly onto how the {@code MultiSourceRanker} routes traffic:
 *
 * <ul>
 *   <li>{@link #HEALTHY} — no demotion; jut.su keeps its configured tier.
 *   <li>{@link #DEGRADED} — soft demotion; jut.su drops one rung in the source ranking so most
 *       traffic shifts to other sources but it's still tried as a fallback.
 *   <li>{@link #UNAVAILABLE} — hard demotion; jut.su is skipped entirely until an operator clears
 *       the signal (typically by re-capturing fixtures or fixing upstream).
 * </ul>
 *
 * <p>The mapping rules live on {@link JutsuDriftDetector} so a single change there propagates
 * everywhere — controller, ranker, ADR.
 */
public enum JutsuDriftHealth {
    HEALTHY,
    DEGRADED,
    UNAVAILABLE
}
