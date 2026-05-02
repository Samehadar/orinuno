package com.orinuno.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Persistent state row for one logical Kodik dump (calendar / serials / films). Backs the {@code
 * orinuno_dump_state} table created in DUMP-1.
 *
 * <p>{@link #lastCheckedAt} is updated on every poll regardless of whether the remote payload
 * changed; {@link #lastChangedAt} is only bumped when ETag / Last-Modified / Content-Length differs
 * from the previously stored value. {@link #consecutiveFailures} drives the health endpoint's
 * "DEGRADED" / "BLOCKED" verdicts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrinunoDumpState {

    private Long id;
    private String dumpName;
    private String dumpUrl;
    private LocalDateTime lastCheckedAt;
    private LocalDateTime lastChangedAt;
    private Integer lastStatus;
    private String lastErrorMessage;
    private String etag;
    private String lastModifiedHeader;
    private Long contentLength;
    private Integer consecutiveFailures;
}
