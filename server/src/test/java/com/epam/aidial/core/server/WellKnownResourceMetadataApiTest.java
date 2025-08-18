package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

public class WellKnownResourceMetadataApiTest extends ResourceBaseTest {

    @Test
    public void testWellKnownResourceMetadata() {
        Response response = send(HttpMethod.GET, "/.well-known/oauth-protected-resource/v1/toolset/test/mcp");

        verifyJsonNotExact(response, 200, """
                {
                  "resource" : "https://localhost/v1/toolset/test/mcp",
                  "authorization_servers" : [ "https://example.com" ]
                }
                """);
    }
}
