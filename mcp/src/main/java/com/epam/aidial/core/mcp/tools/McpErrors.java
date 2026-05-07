package com.epam.aidial.core.mcp.tools;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;

/**
 * Structured-error envelopes for MCP read tools (spec 09 §6.1 — "structured errors with
 * remediation hints"). Returns {@link McpSchema.CallToolResult} with {@code isError: true}
 * and a single {@link McpSchema.TextContent} carrying status, body, and remediation.
 */
public final class McpErrors {

    private McpErrors() {
    }

    public static McpSchema.CallToolResult httpError(int status, String body, String remediation) {
        String text = "HTTP " + status + ": " + truncate(body) + (remediation == null ? "" : ". " + remediation);
        return errorResult(text);
    }

    public static McpSchema.CallToolResult upstreamError(Throwable t) {
        String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        return errorResult("Upstream error: " + msg);
    }

    public static McpSchema.CallToolResult unknownType(String type) {
        return errorResult("Unknown type '" + type + "'. Call dial_describe_schema for the full type catalog.");
    }

    public static McpSchema.CallToolResult recursiveNotSupported(String type) {
        return errorResult("recursive=true is not supported for the flat type '" + type
                + "'. Drop recursive or list a hierarchical type (files, prompts, conversations).");
    }

    public static McpSchema.CallToolResult cursorNotSupported(String type) {
        return errorResult("cursor is not supported for the flat type '" + type
                + "' — single-page listing, no pagination. Drop the cursor argument.");
    }

    public static McpSchema.CallToolResult settingsListNotAllowed() {
        return errorResult("dial_list_resources is not supported for the settings singleton. "
                + "Use dial_get_resource(id='settings/platform/global').");
    }

    public static McpSchema.CallToolResult conflictError(String id) {
        return errorResult("HTTP 409: Resource '" + id + "' already exists. "
                + "Call dial_update_resource to modify it, or pick a new id.");
    }

    public static McpSchema.CallToolResult notFoundError(String id) {
        return errorResult("HTTP 404: Resource '" + id + "' not found. "
                + "Call dial_create_resource to create it, or call dial_get_resource first to verify the id.");
    }

    public static McpSchema.CallToolResult preconditionFailedError(String id, String suppliedEtag) {
        return errorResult("HTTP 412: Stale if_match etag '" + suppliedEtag + "' for resource '" + id
                + "'. Call dial_get_resource to fetch the current etag, then retry.");
    }

    public static McpSchema.CallToolResult message(String text) {
        return errorResult(text);
    }

    static McpSchema.CallToolResult errorResult(String text) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(text)))
                .isError(true)
                .build();
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 512 ? body.substring(0, 512) + "..." : body;
    }
}
