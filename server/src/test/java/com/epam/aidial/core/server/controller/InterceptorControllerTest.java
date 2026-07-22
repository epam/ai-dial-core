package com.epam.aidial.core.server.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class InterceptorControllerTest {

    @Test
    void rewritesDeploymentSegmentToInterceptorName() {
        assertEquals("/openai/deployments/my-interceptor/chat/completions",
                InterceptorController.rewriteDeploymentSegment(
                        "/openai/deployments/initial-model/chat/completions", "my-interceptor"));
    }

    @Test
    void leavesNonMatchingPathUnchanged() {
        assertEquals("/some/other/path",
                InterceptorController.rewriteDeploymentSegment("/some/other/path", "my-interceptor"));
    }
}
