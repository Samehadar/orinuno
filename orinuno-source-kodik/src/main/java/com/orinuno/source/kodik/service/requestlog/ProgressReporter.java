/*
 * ProgressReporter — ADR 0021 §D1a.
 *
 * Reports parse-request progress + phase transitions back to the request
 * log. Ported verbatim from orinuno-app/.../requestlog/ProgressReporter
 * (only the import path of ParseRequestPhase changes).
 */
package com.orinuno.source.kodik.service.requestlog;

import com.orinuno.source.kodik.model.ParseRequestPhase;

public interface ProgressReporter {

    void update(int decoded, int total);

    void phaseTransition(ParseRequestPhase phase);

    /** No-op reporter for synchronous flows that do not write to the request log. */
    ProgressReporter NOOP =
            new ProgressReporter() {
                @Override
                public void update(int decoded, int total) {}

                @Override
                public void phaseTransition(ParseRequestPhase phase) {}
            };
}
