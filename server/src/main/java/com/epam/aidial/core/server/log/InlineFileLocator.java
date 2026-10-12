package com.epam.aidial.core.server.log;

import com.epam.aidial.core.server.log.InlineFileLocations.DataUrl;
import com.epam.aidial.core.server.log.InlineFileLocations.Location;
import com.epam.aidial.core.server.log.InlineFileLocations.LocationSet;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonToken;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Finds the inline files of a body, see {@link InlineFileLocations}. Only bytes before the given limit are read.
 */
@Slf4j
final class InlineFileLocator {

    private static final JsonFactory FACTORY = new JsonFactory();
    // the siblings conditions and mime types are read from
    private static final Set<String> SIBLINGS = Set.of("type", "media_type", "format");
    // longer values are never a mime type or a source type
    private static final int MAX_SIBLING_LENGTH = 256;

    private InlineFileLocator() {
    }

    /**
     * Never throws: a body that cannot be parsed to the end yields the files found before the error, and an
     * unexpected failure yields none, so the body is logged as it is.
     */
    static List<InlineFile> locate(byte[] body, int limit, LocationSet locations) {
        if (locations.isEmpty()) {
            return List.of();
        }
        List<InlineFile> files = new ArrayList<>();
        try {
            int first = firstNonWhitespace(body, limit);
            if (first < 0) {
                return List.of();
            }
            if (body[first] == '{' || body[first] == '[') {
                locateJson(body, first, limit, null, locations, files);
            }
        } catch (Throwable e) {
            // a failure here must not drop the log entry, which GfLogStore.doSave would do
            log.warn("Can't locate inline files", e);
            return List.of();
        }
        files.sort(Comparator.comparingInt(InlineFile::valueStart));
        return files;
    }

    private static int firstNonWhitespace(byte[] body, int limit) {
        int i = 0;
        if (limit >= 3 && (body[0] & 0xFF) == 0xEF && (body[1] & 0xFF) == 0xBB && (body[2] & 0xFF) == 0xBF) {
            i = 3;
        }
        for (; i < limit; i++) {
            byte b = body[i];
            if (b != ' ' && b != '\t' && b != '\n' && b != '\r') {
                return i;
            }
        }
        return -1;
    }

    private static void locateJson(byte[] body, int start, int end, @Nullable Integer chunk, LocationSet locations, List<InlineFile> files) {
        Deque<Frame> frames = new ArrayDeque<>();
        try (JsonParser parser = FACTORY.createParser(body, start, end - start)) {
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token == JsonToken.START_OBJECT) {
                    frames.push(new Frame());
                } else if (token == JsonToken.END_OBJECT) {
                    frames.pop().resolve(chunk, files);
                } else if (token == JsonToken.VALUE_STRING && !frames.isEmpty()) {
                    // null for array elements and root values
                    String name = parser.currentName();
                    if (name == null) {
                        continue;
                    }
                    if (SIBLINGS.contains(name)) {
                        frames.peek().sibling(name, parser.getText());
                    } else if (locations.names().contains(name)) {
                        Candidate candidate = candidate(parser, body, start, end, locations);
                        if (candidate != null) {
                            frames.peek().add(candidate);
                            if (candidate.payloadEnd() < 0) {
                                // the string is still open at the cut: there is nothing more to read
                                break;
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            // the cut or a malformed body: the files found so far still stand
        } finally {
            while (!frames.isEmpty()) {
                frames.pop().resolve(chunk, files);
            }
        }
    }

    @Nullable
    private static Candidate candidate(JsonParser parser, byte[] body, int start, int end, LocationSet locations) {
        JsonPointer pointer = parser.getParsingContext().pathAsPointer();
        Location location = locations.find(segments(pointer));
        if (location == null) {
            return null;
        }
        // relative to the parsed range, see InlineFileLocatorTest.testJacksonTokenOffsetsAreRelativeToTheParsedRange
        int valueStart = start + (int) parser.currentTokenLocation().getByteOffset();
        int payloadEnd = closingQuote(body, valueStart + 1, end);
        DataUrl dataUrl = InlineFileLocations.parseDataUrl(body, valueStart + 1, payloadEnd < 0 ? end : payloadEnd);
        return new Candidate(location, valueStart, payloadEnd, pointer.toString(), dataUrl);
    }

    private static List<String> segments(JsonPointer pointer) {
        List<String> segments = new ArrayList<>();
        for (JsonPointer p = pointer; !p.matches(); p = p.tail()) {
            segments.add(p.getMatchingProperty());
        }
        return segments;
    }

    /**
     * Scans raw bytes, so the payload is never decoded into a String.
     *
     * @return the closing quote, or -1 when the string is still open at {@code end}.
     */
    private static int closingQuote(byte[] body, int from, int end) {
        for (int i = from; i < end; i++) {
            if (body[i] == '\\') {
                i++;
            } else if (body[i] == '"') {
                return i;
            }
        }
        return -1;
    }

    private record Candidate(Location location, int valueStart, int payloadEnd, String path, @Nullable DataUrl dataUrl) {
    }

    /**
     * One JSON object being parsed. Siblings a condition needs may follow the file field, so its candidates are
     * decided when the object ends, or at the cut with the siblings seen so far.
     */
    private static final class Frame {

        private Map<String, String> siblings;
        private List<Candidate> candidates;

        void sibling(String name, String value) {
            if (value.length() <= MAX_SIBLING_LENGTH) {
                if (siblings == null) {
                    siblings = new HashMap<>(4);
                }
                siblings.put(name, value);
            }
        }

        void add(Candidate candidate) {
            if (candidates == null) {
                candidates = new ArrayList<>(1);
            }
            candidates.add(candidate);
        }

        void resolve(@Nullable Integer chunk, List<InlineFile> files) {
            if (candidates == null) {
                return;
            }
            String type = siblingValue("type");
            for (Candidate candidate : candidates) {
                DataUrl dataUrl = candidate.dataUrl();
                boolean file = switch (candidate.location().condition()) {
                    case ALWAYS -> true;
                    case DATA_URL -> dataUrl != null;
                    case BASE64_SOURCE -> "base64".equals(type);
                    case BINARY_TYPE_OR_DATA_URL -> dataUrl != null || InlineFileLocations.isBinaryType(type);
                };
                if (file) {
                    int payloadStart = dataUrl == null ? candidate.valueStart() + 1 : dataUrl.payloadStart();
                    files.add(new InlineFile(candidate.valueStart(), payloadStart, candidate.payloadEnd(), chunk,
                            candidate.path(), dataUrl != null, mime(candidate)));
                }
            }
        }

        @Nullable
        private String mime(Candidate candidate) {
            if (candidate.dataUrl() != null) {
                return candidate.dataUrl().mime();
            }
            Location location = candidate.location();
            String value = location.mimeSibling() == null ? null : siblingValue(location.mimeSibling());
            if (value == null) {
                return null;
            }
            return location.mimePrefix() == null ? value : location.mimePrefix() + value;
        }

        @Nullable
        private String siblingValue(String name) {
            return siblings == null ? null : siblings.get(name);
        }
    }
}
