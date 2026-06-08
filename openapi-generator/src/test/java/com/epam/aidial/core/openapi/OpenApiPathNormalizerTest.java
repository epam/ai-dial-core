package com.epam.aidial.core.openapi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenApiPathNormalizerTest {

    @Test
    void normalizesPathParameterPlaceholdersOnly() {
        assertEquals(
                "/v1/applications/{bucket}/{application_path}",
                OpenApiPathNormalizer.normalizePath("/v1/applications/{Bucket}/{application_path}"));
        assertEquals(
                "/v1/metadata/files/{bucket}/{path}",
                OpenApiPathNormalizer.normalizePath("/v1/metadata/files/{Bucket}/{Path}"));
        assertEquals(
                "/v1/files/{bucket}/{path}",
                OpenApiPathNormalizer.normalizePath("/v1/files/{bucket}/{path}"));
    }

    @Test
    void preservesStaticPathSegments() {
        assertEquals(
                "/v1/Files/{bucket}",
                OpenApiPathNormalizer.normalizePath("/v1/Files/{Bucket}"));
        assertEquals(
                "/openai/deployments/{deployment_name}/chat/completions",
                OpenApiPathNormalizer.normalizePath("/openai/deployments/{Deployment_Name}/chat/completions"));
    }
}