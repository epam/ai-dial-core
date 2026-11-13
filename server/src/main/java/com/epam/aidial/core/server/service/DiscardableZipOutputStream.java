package com.epam.aidial.core.server.service;

import java.io.OutputStream;
import java.util.zip.ZipOutputStream;

/**
 * A failed export must not write the central directory, so the stream is never closed on failure,
 * and its native deflater is released by {@link #discard()} instead.
 */
final class DiscardableZipOutputStream extends ZipOutputStream {

    DiscardableZipOutputStream(OutputStream out) {
        super(out);
    }

    /** Releases the deflater without finishing the archive. Safe to call after {@link #close()}. */
    void discard() {
        def.end();
    }
}
