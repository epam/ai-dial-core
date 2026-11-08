package com.epam.aidial.core.server.service;

import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;

import java.time.Duration;

/**
 * What one export of Applications may read and hold, from the {@code adminExport} settings. A bound that is exceeded fails the
 * whole export before the first byte, so a package never silently omits a resource. The size limits are in characters of a Java string
 * for a stored body and in bytes for redacted output.
 *
 * <p>A stored body is read in full before {@code maxResourceChars} can reject it, so these limits do not bound the memory of one
 * read; see "Limits" in {@code docs/admin-export.md}.
 *
 * @param maxRoots          the selected Applications
 * @param maxNodes          the resources in the closure
 * @param maxDepth          the longest path from a root
 * @param maxEdges          the references found in one body and in all bodies together; the marker references of one body are checked as a
 *                          whole before any is classified, the others are counted one by one
 * @param maxResourceChars  the length of the stored body of one resource
 * @param maxRetainedChars  the limit that is enforced twice: on the summed string length of the stored bodies that were read (counted when a
 *                          body is read, because the stored body of a node that is not yet examined is held until its turn), and on the summed
 *                          byte length of the redacted bytes held until the archive is written. Either sum above it fails the export, so the heap
 *                          for bodies is up to about twice the limit plus the largest transient body
 * @param deadline          the time from the start of the export to the last byte
 */
public record ExportLimits(int maxRoots, int maxNodes, int maxDepth, int maxEdges, long maxResourceChars, long maxRetainedChars,
                           Duration deadline) {

    private static final long MIB = 1024 * 1024;

    public static ExportLimits defaults() {
        return of(new AdminExportSettings());
    }

    public static ExportLimits of(AdminExportSettings settings) {
        return new ExportLimits(settings.getMaxRoots(), settings.getMaxNodes(), settings.getMaxDepth(), settings.getMaxEdges(),
                settings.getMaxResourceMiB() * MIB, settings.getMaxRetainedMiB() * MIB, Duration.ofMillis(settings.getDeadline()));
    }

    /**
     * @return a deadline that starts counting now
     */
    public Deadline startDeadline() {
        return new Deadline(System.nanoTime() + deadline.toNanos());
    }

    /**
     * @return the failure of an export that exceeded a bound; it names the bound and no value of the export
     */
    public static HttpException exceeded(String bound) {
        return new HttpException(HttpStatus.REQUEST_ENTITY_TOO_LARGE, "The export exceeds a limit: " + bound);
    }

    /**
     * @param expiresAtNanos the {@link System#nanoTime()} at which the export is over
     */
    public record Deadline(long expiresAtNanos) {

        private static final Deadline UNBOUNDED = new Deadline(Long.MAX_VALUE);

        public static Deadline unbounded() {
            return UNBOUNDED;
        }

        /**
         * @return the milliseconds left, at least 1, or -1 for a deadline that never expires
         */
        public long remainingMillis() {
            return expiresAtNanos == Long.MAX_VALUE ? -1 : Math.max(1, (expiresAtNanos - System.nanoTime()) / 1_000_000);
        }

        /**
         * @throws HttpException 504 once the deadline has passed
         */
        public void check() {
            if (expiresAtNanos != Long.MAX_VALUE && System.nanoTime() - expiresAtNanos >= 0) {
                throw new HttpException(HttpStatus.GATEWAY_TIMEOUT, "The export took too long");
            }
        }
    }
}
