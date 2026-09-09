package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DeploymentApiTest extends ResourceBaseTest {

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
}
