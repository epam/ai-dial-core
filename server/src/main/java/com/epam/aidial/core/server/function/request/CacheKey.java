package com.epam.aidial.core.server.function.request;

/**
 * A single upstream cache candidate: the concrete prefix path it was built for (e.g.
 * {@code prefix.body.messages[2].content[1]}), the rolling hash of everything up to and including it,
 * and whether it carries an explicit cache breakpoint.
 */
public record CacheKey(String path, String hash, boolean hasBreakpoint) {
}
