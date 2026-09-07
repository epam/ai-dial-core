package com.epam.aidial.core.server.layout;

import java.util.Map;

/**
 * What a caller observes. The comparison is over these three fields and nothing else — physical paths are
 * <em>supposed</em> to differ between layouts, so stored artifacts are not compared. {@code etag} travels in
 * the headers and is derived from content, which is how content equality is checked without touching paths.
 */
public record RecordedResponse(int status, String body, Map<String, String> headers) {
}
