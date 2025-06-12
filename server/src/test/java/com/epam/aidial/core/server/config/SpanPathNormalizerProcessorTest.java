package com.epam.aidial.core.server.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SpanPathNormalizerProcessorTest {

    @Test
    void testNormalizePathWithRouteTemplates() {
        // Files
        assertEquals("/v1/files/{bucket}/{path}",
                SpanPathNormalizer.normalizePath("/v1/files/user123/document.pdf"));

        // Deployments
        assertEquals("/openai/deployments/{id}",
                SpanPathNormalizer.normalizePath("/openai/deployments/gpt-4"));

        // Models
        assertEquals("/openai/models/{id}",
                SpanPathNormalizer.normalizePath("/openai/models/gpt-3.5-turbo"));

        // Conversations
        assertEquals("/v1/{resourceType}/{bucket}/{path}",
                SpanPathNormalizer.normalizePath("/v1/conversations/user123/conv_456"));

        // Applications
        assertEquals("/openai/applications/{id}",
                SpanPathNormalizer.normalizePath("/openai/applications/my-app"));

        // Deployment features
        assertEquals("/v1/deployments/{id}/tokenize",
                SpanPathNormalizer.normalizePath("/v1/deployments/gpt-4/tokenize"));

        // Invitations
        assertEquals("/v1/invitations/{id}",
                SpanPathNormalizer.normalizePath("/v1/invitations/inv123"));

        // Static paths should remain unchanged
        assertEquals("/health",
                SpanPathNormalizer.normalizePath("/health"));
        assertEquals("/v1/user/info",
                SpanPathNormalizer.normalizePath("/v1/user/info"));
    }

    @Test
    void testNormalizeComplexPaths() {
        // POST deployment with action
        assertEquals("/openai/deployments/{id}/{action}",
                SpanPathNormalizer.normalizePath("/openai/deployments/gpt-4/chat/completions"));

        // Resource operations
        assertEquals("/v1/ops/resource/{operation}",
                SpanPathNormalizer.normalizePath("/v1/ops/resource/move"));

        // Publication operations
        assertEquals("/v1/ops/publication/{operation}",
                SpanPathNormalizer.normalizePath("/v1/ops/publication/create"));
    }
}
