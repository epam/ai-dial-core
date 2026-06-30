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

public class DeploymentApiTest extends ResourceBaseTest {

    @DialConfigLocation("dial-config/deployment-interfaces-listing.json")
    @Test
    public void testListDeployments() throws JsonProcessingException {
        Response response = send(HttpMethod.GET, "/v1/deployments", null, null);
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(4, body.size());

        // typed interface types are surfaced in the `interfaces` array next to the UI categories;
        // embedding models expose openaiChatCompletions since their endpoint lives under that key
        Map<String, Set<String>> interfacesById = collectInterfaces(body);
        assertEquals(Set.of("embedding", "openaiChatCompletions"), interfacesById.get("embedding-ada"));
        assertEquals(Set.of("chat", "openaiChatCompletions"), interfacesById.get("gpt-4"));

        response = send(HttpMethod.GET, "/v1/deployments", "interface_type=all,mcp", null);
        verify(response, 200);
        body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(4, body.size());

        response = send(HttpMethod.GET, "/v1/deployments", "interface_type=mcp", null);
        verify(response, 200);
        body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(1, body.size());

        response = send(HttpMethod.GET, "/v1/deployments", "interface_type=mcp,embedding", null);
        verify(response, 200);
        body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(2, body.size());

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
        assertEquals(2, body.size());

        response = send(HttpMethod.GET, "/v1/deployments", "interface_type=chat", null);
        verify(response, 200);
        body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(3, body.size());

        response = send(HttpMethod.GET, "/v1/deployments", "interface_type=chat,mcp", null);
        verify(response, 200);
        body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(4, body.size());

        response = send(HttpMethod.GET, "/v1/deployments", "interface_type=custom_ui", null);
        verify(response, 200);
        body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(1, body.size());
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
