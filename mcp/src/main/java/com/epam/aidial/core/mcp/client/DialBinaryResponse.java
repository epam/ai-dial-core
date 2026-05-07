package com.epam.aidial.core.mcp.client;

import io.vertx.core.MultiMap;

public record DialBinaryResponse(int statusCode, byte[] body, MultiMap headers) {
}
