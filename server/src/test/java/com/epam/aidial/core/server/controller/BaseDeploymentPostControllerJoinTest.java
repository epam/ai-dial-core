package com.epam.aidial.core.server.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BaseDeploymentPostControllerJoinTest {

    @Test
    public void testJoinBaseUrlAndPath() {
        assertEquals("http://a:5000/openai/v1/responses",
                BaseDeploymentPostController.joinBaseUrlAndPath("http://a:5000", "/openai/v1/responses")
        );
        // trailing slash on base url collapses
        assertEquals("http://a:5000/openai/v1/responses",
                BaseDeploymentPostController.joinBaseUrlAndPath("http://a:5000/", "/openai/v1/responses")
        );
        // multiple trailing slashes on base url
        assertEquals("http://a:5000/x",
                BaseDeploymentPostController.joinBaseUrlAndPath("http://a:5000///", "/x")
        );
        // multiple leading slashes on path collapse to one
        assertEquals("http://a:5000/x",
                BaseDeploymentPostController.joinBaseUrlAndPath("http://a:5000", "///x")
        );
        // prefixed base url is preserved
        assertEquals("http://a:5000/to-responses/openai/v1/responses",
                BaseDeploymentPostController.joinBaseUrlAndPath("http://a:5000/to-responses", "/openai/v1/responses")
        );
        // path without a leading slash gets one
        assertEquals("http://a:5000/x",
                BaseDeploymentPostController.joinBaseUrlAndPath("http://a:5000", "x")
        );
    }
}
