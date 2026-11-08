package com.epam.aidial.core.server.service;

import com.epam.aidial.core.storage.service.LockService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * The {@code adminExport} settings of the export of selected Applications with their dependencies ({@code POST /v1/admin/export}). The
 * export of whole buckets has its own {@code adminSystemExport} settings.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AdminExportSettings {
    /**
     * Milliseconds the export waits for a slow client to take the buffered data before it fails.
     */
    private long stallTimeout = 60_000;
    /** The Applications that one export may name. */
    private int maxRoots = 100;
    /** The resources of the closure of one export. */
    private int maxNodes = 500;
    /** The longest chain of references that an export follows from a root. */
    private int maxDepth = 10;
    /** The references found in one body and in all bodies of one export together. */
    private int maxEdges = 10_000;
    /** The size of the stored body of one resource, in MiB of string length. */
    private long maxResourceMiB = 4;
    /**
     * The limit, in MiB, of two sums of one export: the string length of the stored bodies that were read, and the byte length of the
     * redacted bytes held until the archive is written. Either one above it fails the export, so the heap for bodies is up to about
     * twice this value plus the largest transient body.
     */
    private long maxRetainedMiB = 32;
    /**
     * Milliseconds from the start of an export to its last byte; it holds the one slot meanwhile. The slot is a lease of the lock period
     * that is not renewed, so the deadline and one stall must fit in it.
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
        if (maxRoots <= 0 || maxNodes <= 0 || maxDepth <= 0 || maxEdges <= 0 || maxResourceMiB <= 0 || maxRetainedMiB <= 0) {
            throw new IllegalArgumentException("adminExport limits (maxRoots, maxNodes, maxDepth, maxEdges, maxResourceMiB, "
                    + "maxRetainedMiB) must be positive");
        }
    }
}
