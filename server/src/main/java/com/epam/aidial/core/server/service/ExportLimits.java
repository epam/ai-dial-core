package com.epam.aidial.core.server.service;

import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;

import java.time.Duration;

/**
 * What one export of Applications may read, from the {@code adminExport} settings. A bound that is exceeded fails the export, so a
 * package never silently omits a resource: before the response head it is a status, after it the stream is cut. The size limit counts
 * characters of a Java string.
 *
 * <p>A stored body is read in full before {@code maxResourceChars} can reject it, so this limit does not bound the memory of one
 * read; see "Limits" in {@code docs/admin-export.md}.
 */
public record ExportLimits(int maxRoots, int maxNodes, int maxDepth, int maxEdges, long maxResourceChars, Duration deadline) {

    private static final long MIB = 1024 * 1024;

    public static ExportLimits of(AdminExportSettings settings) {
        return new ExportLimits(settings.getMaxRoots(), settings.getMaxNodes(), settings.getMaxDepth(), settings.getMaxEdges(),
                settings.getMaxResourceMiB() * MIB, Duration.ofMillis(settings.getDeadline()));
    }

    public Deadline startDeadline() {
        return new Deadline(System.nanoTime() + deadline.toNanos());
    }

    /**
     * @return the failure of an export that exceeded a bound; it names the bound and no value of the export
     */
    public static HttpException newExceededException(String bound) {
        return new HttpException(HttpStatus.REQUEST_ENTITY_TOO_LARGE, "The export exceeds a limit: " + bound);
    }

    /**
     * @param expiresAtNanos the {@link System#nanoTime()} at which the export is over
     */
    public record Deadline(long expiresAtNanos) {

        /**
         * @return the milliseconds left, at least 1
         */
        public long computeRemainingMillis() {
            return Math.max(1, (expiresAtNanos - System.nanoTime()) / 1_000_000);
        }

        /**
         * @throws HttpException 504 once the deadline has passed
         */
        public void check() {
            if (System.nanoTime() - expiresAtNanos >= 0) {
                throw new HttpException(HttpStatus.GATEWAY_TIMEOUT, "The export took too long");
            }
        }
    }
}
