package com.epam.aidial.core.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RegexUtilTest {

    @ParameterizedTest
    @MethodSource("datasource")
    void getName(String pathPattern, String path, String expectedName) {
        var pattern = Pattern.compile(pathPattern);
        var groups = List.of("id", "bucket", "path");
        assertEquals(expectedName, RegexUtil.replaceNamedGroups(pattern, path, groups));
    }

    public static List<Arguments> datasource() {
        return List.of(
                Arguments.of(
                        "^/+openai/deployments/(?<id>.+?)/(completions|chat/completions|embeddings)$",
                        "/openai/deployments/gpt/chat/completions",
                        "/openai/deployments/{id}/chat/completions"
                ),
                Arguments.of(
                        "^/+openai/deployments/(?<id>.+?)/(completions|chat/completions|embeddings)$",
                        "/openai/deployments/l/embeddings",
                        "/openai/deployments/{id}/embeddings"
                ),
                Arguments.of(
                        "^/+openai/deployments/(?<id>.+?)$",
                        "/openai/deployments/gpt",
                        "/openai/deployments/{id}"
                ),
                Arguments.of(
                        "^/+openai/deployments/(?<id>.+?)$",
                        "/openai/deployments/l",
                        "/openai/deployments/{id}"
                ),
                Arguments.of(
                        "^/v1/bucket$",
                        "/v1/bucket",
                        "/v1/bucket"
                ),
                Arguments.of(
                        "^/v1/files/(?<bucket>[a-zA-Z0-9]+)/(?<path>.*)",
                        "/v1/files/GHyJjv7CfrGRiv6RNajWsde7ET6bGTrbD45JatdSfsPK/path/to/file.pdf",
                        "/v1/files/{bucket}/{path}"
                ),
                Arguments.of(
                        "^/v1/files/(?<bucket>[a-zA-Z0-9]+)/(?<path>.*)",
                        "/v1/files/f/i/l/e.pdf",
                        "/v1/files/{bucket}/{path}"
                ),
                Arguments.of(
                        "^/v1/files/(?<bucket>[a-zA-Z0-9]+)/(?<path>.*)",
                        "/v1/files/f/f",
                        "/v1/files/{bucket}/{path}"
                ),
                Arguments.of(
                        "^/v1/ops/resource/share/(create|list|discard|revoke|copy)$",
                        "/v1/ops/resource/share/list",
                        "/v1/ops/resource/share/list"
                ),
                Arguments.of(
                        "^/v1/invitations/(?<id>[a-zA-Z0-9]+)$",
                        "/v1/invitations/123abc",
                        "/v1/invitations/{id}"
                ),
                Arguments.of(
                        "^/api/(?<somethingElse>.+?)$",
                        "/api/123",
                        "/api/123"
                ),
                Arguments.of(
                        "^/v1/(?<somethingElse>.+?)$",
                        "/api/123",
                        "/api/123"
                )
        );
    }
}