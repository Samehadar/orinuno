package com.orinuno.service.requestlog;

import com.orinuno.model.ParseRequestPhase;

/**
 * Reports parse-request progress and phase transitions back to the request log. Implementations
 * decide whether to flush immediately or batch updates. Phase transitions are semantic boundaries
 * and should always flush immediately to keep observers in sync.
 */
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
