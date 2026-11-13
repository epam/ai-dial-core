package com.epam.aidial.core.server.service;

import com.epam.aidial.core.storage.service.LockService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** The {@code adminExport} settings of {@code POST /v1/admin/export}; the export of whole buckets has its own {@code adminSystemExport}. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AdminExportSettings {
    /** Milliseconds the export waits for a slow client to take the buffered data before it fails. */
    private long stallTimeout = 60_000;
    /**
     * Milliseconds from the start of an export to its last byte, while it holds the bucket locks. The lock lease is not renewed, so the
     * deadline and one stall must fit in it.
     */
    private long deadline = 180_000;

    public void validate() {
        if (stallTimeout <= 0 || stallTimeout >= LockService.LOCK_PERIOD.toMillis()) {
            throw new IllegalArgumentException("adminExport.stallTimeout must be positive and below the lock period of "
                    + LockService.LOCK_PERIOD.toMillis() + " ms, but got: " + stallTimeout);
        }
        if (deadline <= 0 || deadline + stallTimeout >= LockService.LOCK_PERIOD.toMillis()) {
            throw new IllegalArgumentException("adminExport.deadline must be positive and, with the stall timeout, below the lock "
                    + "period of " + LockService.LOCK_PERIOD.toMillis() + " ms, but got: " + deadline);
        }
    }
}
