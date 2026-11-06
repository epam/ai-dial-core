package com.epam.aidial.core.server;

import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.server.data.FeaturesData;
import com.epam.aidial.core.server.util.ProxyUtil;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterfaceFeaturesApiTest extends ResourceBaseTest {

    @Test
    @DialConfigLocation("dial-config/interface-feature-overrides.json")
    void translatorReceivesTheRequestedInterfacesFeatures() {
        FeaturesData features = sendAndReadFeatures(InterfaceType.ANTHROPIC_MESSAGES, "feature-translator");

        assertTrue(features.isTools());
        assertFalse(features.isTemperature());
        assertEquals(List.of("max"), features.getReasoningEfforts());
    }

    @ParameterizedTest
    @EnumSource(InterfaceType.class)
    @DialConfigLocation("dial-config/interface-feature-overrides.json")
    void forwardsInheritedFeaturesAndOnlyTheRequestedInterfacesOverrides(InterfaceType type) {
        FeaturesData features = sendAndReadFeatures(type, "feature-inheritance");

        assertTrue(features.isTools());
        assertTrue(features.isTemperature());
        assertTrue(features.isSystemPrompt());
        assertEquals(type == InterfaceType.ANTHROPIC_MESSAGES ? List.of("low", "medium", "high", "xhigh", "max")
                : List.of("low", "medium", "high"), features.getReasoningEfforts());
    }

    @ParameterizedTest
    @EnumSource(InterfaceType.class)
    @DialConfigLocation("dial-config/interface-feature-overrides.json")
    void forwardsFalseAndEmptyListOverrides(InterfaceType type) {
        FeaturesData features = sendAndReadFeatures(type, "feature-overrides");

        assertFalse(features.isTools());
        assertFalse(features.isTemperature());
        assertTrue(features.isSystemPrompt());
        assertEquals(List.of(), features.getReasoningEfforts());
    }

    private FeaturesData sendAndReadFeatures(InterfaceType type, String model) {
        String path = switch (type) {
            case OPENAI_CHAT_COMPLETIONS -> "/openai/deployments/" + model + "/chat/completions";
            case OPENAI_EMBEDDINGS -> "/openai/deployments/" + model + "/embeddings";
            case OPENAI_RESPONSES -> "/openai/v1/responses";
            case ANTHROPIC_MESSAGES -> "/anthropic/v1/messages";
        };
        String body = """
                {"model":"%s","messages":[{"role":"user","content":"hello"}],
                 "input":"hello","max_tokens":16,"store":false}
                """.formatted(model);
        AtomicReference<String> featureHeader = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, path, request -> {
                featureHeader.set(request.getHeader(Proxy.HEADER_DEPLOYMENT_FEATURES));
                return TestWebServer.createResponse(200, "{}", "Content-Type", "application/json");
            });

            Response response = send(HttpMethod.POST, path, null, body,
                    "Content-Type", "application/json", Proxy.HEADER_DEPLOYMENT_FEATURES, "spoofed");

            assertEquals(200, response.status(), response.body());
            assertNotNull(featureHeader.get());
            return ProxyUtil.convertToObject(featureHeader.get(), FeaturesData.class);
        }
    }
}
