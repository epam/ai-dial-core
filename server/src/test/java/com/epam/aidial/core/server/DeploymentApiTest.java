package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DeploymentApiTest extends ResourceBaseTest {

    @DialConfigLocation("dial-config/deployment-interfaces-listing.json")
    @Test
    public void testListDeployments() throws JsonProcessingException {
        Response response = send(HttpMethod.GET, "/v1/deployments", null, null);
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(4, body.size());

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
}
