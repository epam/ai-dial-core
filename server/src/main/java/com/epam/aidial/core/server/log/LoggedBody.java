package com.epam.aidial.core.server.log;

import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.server.log.InlineFileLocations.LocationSet;
import com.google.common.annotations.VisibleForTesting;
import io.vertx.core.buffer.Buffer;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * One body field as the analytics log writes it.
 *
 * @param bytes     what is written: at most the first {@link #MAX_BYTES} of the original body, payloads removed or the
 *                  cut moved back as the settings say.
 * @param truncated whether the original body was longer than the limit, i.e. whether {@code >>} follows.
 * @param files     the inline files that start before the cut, in document order; empty when the body was not scanned.
 */
record LoggedBody(Buffer bytes, boolean truncated, List<LoggedFile> files) {

    // Max allowed size is 4 mb for request/response body
    static final int MAX_BYTES = 4 * 1024 * 1024;

    enum Status {
        INCLUDED,
        PARTIAL,
        EXCLUDED
    }

    /**
     * @param offset null unless the payload is in the log and the logged bytes are valid UTF-8.
     * @param length null when {@code offset} is.
     * @param size   null when the file crosses the cut.
     */
    record LoggedFile(InlineFile file, Status status, @Nullable Integer offset, @Nullable Integer length, @Nullable Integer size) {
    }

    @Nullable
    static LoggedBody prepare(@Nullable Buffer body, @Nullable InterfaceType type, AnalyticsSettings settings) {
        return prepare(body, type, settings, MAX_BYTES);
    }

    @VisibleForTesting
    @Nullable
    static LoggedBody prepare(@Nullable Buffer body, @Nullable InterfaceType type, AnalyticsSettings settings, int maxBytes) {
        if (body == null) {
            return null;
        }
        boolean truncated = body.length() > maxBytes;
        int cut = truncated ? maxBytes : body.length();
        LocationSet locations = InlineFileLocations.forInterface(type);
        if (locations.isEmpty() || !needsScan(settings, truncated)) {
            return asIs(body, truncated, cut);
        }
        byte[] bytes = body.getBytes(0, cut);
        List<InlineFile> found = InlineFileLocator.locate(bytes, cut, locations);
        if (found.isEmpty()) {
            return asIs(body, truncated, cut);
        }
        return removeAndCut(bytes, cut, truncated, found, settings);
    }

    private static boolean needsScan(AnalyticsSettings settings, boolean truncated) {
        return !settings.collectInlineFiles() || settings.collectInlineFileIndex() || truncated && !settings.collectTruncatedInlineFiles();
    }

    private static LoggedBody asIs(Buffer body, boolean truncated, int cut) {
        return new LoggedBody(truncated ? body.slice(0, cut) : body, truncated, List.of());
    }

    private static LoggedBody removeAndCut(byte[] bytes, int cut, boolean truncated, List<InlineFile> found, AnalyticsSettings settings) {
        boolean remove = !settings.collectInlineFiles();
        boolean cutBefore = remove || !settings.collectTruncatedInlineFiles();
        // offsets count bytes, but the log decodes the body to text: a malformed sequence would shift every one after it
        boolean offsets = !remove && isValidUtf8(bytes, cut);
        Buffer kept = Buffer.buffer(cut);
        int position = 0;
        int end = cut;
        List<LoggedFile> files = new ArrayList<>(found.size());
        for (InlineFile file : found) {
            if (file.crossesCut()) {
                // the locator stops at a file crossing the cut, so it is the last one
                if (cutBefore) {
                    end = file.valueStart();
                    files.add(new LoggedFile(file, Status.EXCLUDED, null, null, null));
                } else {
                    int length = cut - file.payloadStart();
                    files.add(new LoggedFile(file, Status.PARTIAL, offsets ? file.payloadStart() : null, offsets ? length : null, null));
                }
                break;
            }
            int size = file.payloadEnd() - file.payloadStart();
            if (remove) {
                kept.appendBytes(bytes, position, file.payloadStart() - position);
                position = file.payloadEnd();
                files.add(new LoggedFile(file, Status.EXCLUDED, null, null, size));
            } else {
                files.add(new LoggedFile(file, Status.INCLUDED, offsets ? file.payloadStart() : null, offsets ? size : null, size));
            }
        }
        kept.appendBytes(bytes, position, end - position);
        return new LoggedBody(kept, truncated, List.copyOf(files));
    }

    /**
     * Whether the bytes decode without replacement characters, as {@code new String(bytes, UTF_8)} would. A sequence
     * cut short by {@code length} is accepted: it can only sit at the cut, after every payload that has an offset.
     */
    private static boolean isValidUtf8(byte[] bytes, int length) {
        int i = 0;
        while (i < length) {
            int lead = bytes[i] & 0xFF;
            if (lead < 0x80) {
                i++;
                continue;
            }
            int continuations;
            int secondMin = 0x80;
            int secondMax = 0xBF;
            if (lead >= 0xC2 && lead <= 0xDF) {
                continuations = 1;
            } else if (lead >= 0xE0 && lead <= 0xEF) {
                continuations = 2;
                if (lead == 0xE0) {
                    secondMin = 0xA0;
                } else if (lead == 0xED) {
                    secondMax = 0x9F;
                }
            } else if (lead >= 0xF0 && lead <= 0xF4) {
                continuations = 3;
                if (lead == 0xF0) {
                    secondMin = 0x90;
                } else if (lead == 0xF4) {
                    secondMax = 0x8F;
                }
            } else {
                return false;
            }
            for (int k = 1; k <= continuations; k++) {
                if (i + k >= length) {
                    return true;
                }
                int b = bytes[i + k] & 0xFF;
                if (b < (k == 1 ? secondMin : 0x80) || b > (k == 1 ? secondMax : 0xBF)) {
                    return false;
                }
            }
            i += continuations + 1;
        }
        return true;
    }
}
