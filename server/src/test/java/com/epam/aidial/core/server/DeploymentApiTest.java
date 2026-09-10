package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DeploymentApiTest extends ResourceBaseTest {

    private static final double DELTA = 1e-9;

    @DialConfigLocation("dial-config/deployment-interfaces-listing.json")
    @Test
    public void testListDeployments() throws JsonProcessingException {
        Response response = send(HttpMethod.GET, "/v1/deployments", null, null);
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(5, body.size());

        // typed interface types are surfaced in the `interfaces` array next to the UI categories;
        // a pre-interfaces endpoint declares the type matching what the deployment says it is
        Map<String, Set<String>> interfacesById = collectInterfaces(body);
        assertEquals(Set.of("embedding", "openaiEmbeddings"), interfacesById.get("embedding-ada"));
        assertEquals(Set.of("chat", "openaiChatCompletions"), interfacesById.get("gpt-4"));
        // schema-rich application declared directly in config: endpoint and mcp are resolved from its
        // application type schema rather than set as literal fields, so they must be resolved before filtering
        assertEquals(Set.of("chat", "mcp", "custom_ui", "openaiChatCompletions"), interfacesById.get("schema-app"));

        response = send(HttpMethod.GET, "/v1/deployments", "interface_type=all,mcp", null);
        verify(response, 200);
        body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(5, body.size());

        response = send(HttpMethod.GET, "/v1/deployments", "interface_type=mcp", null);
        verify(response, 200);
        body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(2, body.size());

        response = send(HttpMethod.GET, "/v1/deployments", "interface_type=mcp,embedding", null);
        verify(response, 200);
        body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(3, body.size());

        response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my%20app", null, """
                {
                "display_name": "My App",
                "display_version": "1.0",
                "icon_url": "http://apprunner/icon.svg",
                "description": "My app Description",
                "applicationTypeSchemaId": "https://mydial.somewhere.com/custom_application_schemas/specific_toolset_type",
                "applicationProperties": {
                  "property1": "foo",
                  "property2": "bar"
                  }
                }
                """);
        verify(response, 200);

        response = send(HttpMethod.GET, "/v1/deployments", "interface_type=mcp", null);
        verify(response, 200);
        body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(3, body.size());

        response = send(HttpMethod.GET, "/v1/deployments", "interface_type=chat", null);
        verify(response, 200);
        body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(4, body.size());
        assertTrue(collectInterfaces(body).containsKey("schema-app"));

        response = send(HttpMethod.GET, "/v1/deployments", "interface_type=chat,mcp", null);
        verify(response, 200);
        body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(5, body.size());

        response = send(HttpMethod.GET, "/v1/deployments", "interface_type=custom_ui", null);
        verify(response, 200);
        body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(2, body.size());
        assertTrue(collectInterfaces(body).containsKey("schema-app"));
    }

    @DialConfigLocation("dial-config/deployment-interfaces-listing.json")
    @Test
    public void testGetDeployment() throws JsonProcessingException {
        // a model, an application and a toolset are returned in the very same shape the listing uses
        JsonNode model = readDeployment(send(HttpMethod.GET, "/v1/deployments/gpt-4"));
        assertEquals("model", model.get("object").asText());
        assertEquals(Set.of("chat", "openaiChatCompletions"), interfacesOf(model));

        JsonNode application = readDeployment(send(HttpMethod.GET, "/v1/deployments/schema-app"));
        assertEquals("application", application.get("object").asText());
        assertEquals(Set.of("chat", "mcp", "custom_ui", "openaiChatCompletions"), interfacesOf(application));

        JsonNode toolSet = readDeployment(send(HttpMethod.GET, "/v1/deployments/git"));
        assertEquals("toolset", toolSet.get("object").asText());
        assertEquals(Set.of("mcp"), interfacesOf(toolSet));

        verify(send(HttpMethod.GET, "/v1/deployments/unknown-deployment"), 404);
    }

    @DialConfigLocation("dial-config/deployment-interfaces-listing.json")
    @Test
    public void testGetCustomApplicationDeployment() throws JsonProcessingException {
        Response response = send(HttpMethod.PUT, "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my%20app", null, """
                {
                "display_name": "My App",
                "display_version": "1.0",
                "icon_url": "http://apprunner/icon.svg",
                "description": "My app Description",
                "applicationTypeSchemaId": "https://mydial.somewhere.com/custom_application_schemas/specific_toolset_type",
                "applicationProperties": {
                  "property1": "foo",
                  "property2": "bar"
                  }
                }
                """);
        verify(response, 200);

        String deploymentId = "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my%20app";
        JsonNode application = readDeployment(send(HttpMethod.GET, "/v1/deployments/" + deploymentId));
        assertEquals("application", application.get("object").asText());
        assertEquals(deploymentId, application.get("id").asText());
        assertEquals(Set.of("chat", "mcp", "custom_ui", "openaiChatCompletions"), interfacesOf(application));
    }

    @Test
    public void testGetForbiddenDeployment() {
        // gpt-4 is restricted to the power-user role, proxyKey1 has the default one
        verify(send(HttpMethod.GET, "/v1/deployments/gpt-4"), 403);
        // an interceptor is a deployment, but it is not a part of the deployment listing
        verify(send(HttpMethod.GET, "/v1/deployments/interceptor1"), 404);
    }

    @DialConfigLocation("dial-config/deployment-interfaces-features.json")
    @Test
    public void testChatCompletionFeatureFollowsInterfaces() throws JsonProcessingException {
        Map<String, JsonNode> deployments = collectFeatures(send(HttpMethod.GET, "/v1/deployments", null, null));
        assertTrue(deployments.get("model-iface-only").get("chat_completion").asBoolean());
        assertTrue(deployments.get("model-iface-only").get("responses_api").asBoolean());
        assertTrue(deployments.get("app-iface-only").get("chat_completion").asBoolean());
        assertTrue(deployments.get("app-legacy").get("chat_completion").asBoolean());
        // anthropicMessages alone does not make the deployment an OpenAI chat-completions one
        assertFalse(deployments.get("model-anthropic-only").get("chat_completion").asBoolean());

        Map<String, JsonNode> models = collectFeatures(send(HttpMethod.GET, "/openai/models", null, null));
        assertTrue(models.get("model-iface-only").get("chat_completion").asBoolean());
        assertTrue(models.get("model-iface-only").get("responses_api").asBoolean());
        assertFalse(models.get("model-anthropic-only").get("chat_completion").asBoolean());

        Map<String, JsonNode> applications = collectFeatures(send(HttpMethod.GET, "/openai/applications", null, null));
        assertTrue(applications.get("app-iface-only").get("chat_completion").asBoolean());
        assertTrue(applications.get("app-legacy").get("chat_completion").asBoolean());
    }

    @DialConfigLocation("dial-config/deployment-interface-configs.json")
    @Test
    public void testInterfaceConfigsListEffectiveFeaturesAndDefaultsPerInterface() throws JsonProcessingException {
        Map<String, JsonNode> deploymentsById = collectDeployments(send(HttpMethod.GET, "/v1/deployments", null, null));

        JsonNode modelConfigs = deploymentsById.get("gpt-5.4-mini").get("interface_configs");
        assertEquals(Set.of("openaiChatCompletions", "openaiResponses", "anthropicMessages"), fieldNames(modelConfigs));

        // the API-surface flags name the API each interface itself is, unlike the deployment-level
        // features where they name the APIs the deployment serves at all
        JsonNode chat = modelConfigs.get("openaiChatCompletions");
        assertTrue(chat.get("features").get("chat_completion").asBoolean());
        assertFalse(chat.get("features").get("responses_api").asBoolean());
        assertTrue(chat.get("features").get("system_prompt").asBoolean());
        assertEquals(1, chat.get("defaults").get("temperature").asDouble(), DELTA);

        JsonNode responses = modelConfigs.get("openaiResponses");
        assertFalse(responses.get("features").get("chat_completion").asBoolean());
        assertTrue(responses.get("features").get("responses_api").asBoolean());
        assertEquals(1, responses.get("defaults").get("temperature").asDouble(), DELTA);

        JsonNode anthropic = modelConfigs.get("anthropicMessages");
        assertFalse(anthropic.get("features").get("chat_completion").asBoolean());
        assertFalse(anthropic.get("features").get("responses_api").asBoolean());
        // the interface-level reasoning efforts and defaults replace the deployment-level ones ...
        assertEquals(List.of("low", "medium", "high", "xhigh", "max"),
                textValues(anthropic.get("features").get("reasoning_efforts")));
        assertEquals(0.5, anthropic.get("defaults").get("temperature").asDouble(), DELTA);
        // ... for that interface only: the deployment-level view keeps its own values
        JsonNode model = deploymentsById.get("gpt-5.4-mini");
        assertEquals(List.of("low", "medium"), textValues(model.get("features").get("reasoning_efforts")));
        assertEquals(1, model.get("defaults").get("temperature").asDouble(), DELTA);

        // the default headers follow the same resolution: the deployment-level set, overlaid per interface
        assertEquals("cache-priority", chat.get("default_headers").get("x-dial-cache-policy").asText());
        assertEquals("foo-bar", chat.get("default_headers").get("x-dial-custom-header").asText());
        assertEquals("foo-bar", responses.get("default_headers").get("x-dial-custom-header").asText());
        assertEquals("cache-priority", anthropic.get("default_headers").get("x-dial-cache-policy").asText());
        // the interface entry overrides the header it names and adds the one the deployment level lacks
        assertEquals("foo-bar-2", anthropic.get("default_headers").get("x-dial-custom-header").asText());
        assertEquals("some-value", anthropic.get("default_headers").get("x-dial-custom-header-2").asText());

        // a legacy endpoint is advertised as the interface matching what the model says it is
        assertEquals(Set.of("openaiChatCompletions"),
                fieldNames(deploymentsById.get("model-legacy").get("interface_configs")));
        // a deployment with no default headers lists an empty object, like defaults
        JsonNode legacyChat = deploymentsById.get("model-legacy").get("interface_configs").get("openaiChatCompletions");
        assertEquals(ProxyUtil.MAPPER.createObjectNode(), legacyChat.get("default_headers"));
        assertEquals(Set.of("openaiEmbeddings"),
                fieldNames(deploymentsById.get("embedding-ada").get("interface_configs")));

        // applications carry their interface entry too, and one serving no typed interface omits the field
        JsonNode appConfigs = deploymentsById.get("app-iface").get("interface_configs");
        assertEquals(Set.of("openaiChatCompletions"), fieldNames(appConfigs));
        assertEquals(0.1, appConfigs.get("openaiChatCompletions").get("defaults").get("temperature").asDouble(), DELTA);
        assertFalse(deploymentsById.get("app-ui-only").has("interface_configs"));

        // the openai listings and the single-deployment endpoints return the same configs
        assertEquals(modelConfigs, collectDeployments(
                send(HttpMethod.GET, "/openai/models", null, null)).get("gpt-5.4-mini").get("interface_configs"));
        assertEquals(modelConfigs, collectDeployments(
                send(HttpMethod.GET, "/openai/deployments", null, null)).get("gpt-5.4-mini").get("interface_configs"));
        assertEquals(appConfigs, collectDeployments(
                send(HttpMethod.GET, "/openai/applications", null, null)).get("app-iface").get("interface_configs"));
        assertEquals(modelConfigs, readDeployment(
                send(HttpMethod.GET, "/v1/deployments/gpt-5.4-mini")).get("interface_configs"));
        assertEquals(modelConfigs, readDeployment(
                send(HttpMethod.GET, "/openai/deployments/gpt-5.4-mini")).get("interface_configs"));
    }

    /**
     * Maps deployment id to its {@code features} object. Handles both the bare array returned by
     * {@code /v1/deployments} and the {@code {"data": [...]}} envelope of the legacy listings.
     */
    private static Map<String, JsonNode> collectFeatures(Response response) throws JsonProcessingException {
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        JsonNode deployments = body.isArray() ? body : body.get("data");

        Map<String, JsonNode> result = new HashMap<>();
        for (JsonNode deployment : deployments) {
            result.put(deployment.get("id").asText(), deployment.get("features"));
        }
        return result;
    }

    private static JsonNode readDeployment(Response response) throws JsonProcessingException {
        verify(response, 200);
        return ProxyUtil.MAPPER.readTree(response.body());
    }

    private static Set<String> interfacesOf(JsonNode deployment) {
        Set<String> interfaces = new HashSet<>();
        for (JsonNode iface : deployment.get("interfaces")) {
            interfaces.add(iface.asText());
        }
        return interfaces;
    }

    private static Map<String, Set<String>> collectInterfaces(JsonNode body) {
        Map<String, Set<String>> result = new HashMap<>();
        for (JsonNode deployment : body) {
            Set<String> interfaces = new HashSet<>();
            for (JsonNode iface : deployment.get("interfaces")) {
                interfaces.add(iface.asText());
            }
            result.put(deployment.get("id").asText(), interfaces);
        }
        return result;
    }

    /**
     * Maps deployment id to its whole listing entry. Handles both the bare array returned by
     * {@code /v1/deployments} and the {@code {"data": [...]}} envelope of the legacy listings.
     */
    private static Map<String, JsonNode> collectDeployments(Response response) throws JsonProcessingException {
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        JsonNode deployments = body.isArray() ? body : body.get("data");

        Map<String, JsonNode> result = new HashMap<>();
        for (JsonNode deployment : deployments) {
            result.put(deployment.get("id").asText(), deployment);
        }
        return result;
    }

    private static Set<String> fieldNames(JsonNode object) {
        Set<String> names = new HashSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : array) {
            values.add(value.asText());
        }
        return values;
    }
}
