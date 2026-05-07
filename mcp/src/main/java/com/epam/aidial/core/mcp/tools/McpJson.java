package com.epam.aidial.core.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class McpJson {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private McpJson() {
    }
}
