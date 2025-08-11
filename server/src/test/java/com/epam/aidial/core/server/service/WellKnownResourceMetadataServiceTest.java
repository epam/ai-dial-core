package com.epam.aidial.core.server.service;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.HostAndPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class WellKnownResourceMetadataServiceTest {

    @Mock
    private HttpServerRequest request;

    @Test
    void constructor_withStringAuthorizationServers() {
        JsonObject mcp = new JsonObject()
                .put("security", new JsonObject()
                        .put("authorizationServers", "https://auth.example.com")
                        .put("resourceHost", "example.com"));
        WellKnownResourceMetadataService service = new WellKnownResourceMetadataService(mcp);

        assertEquals(List.of("https://auth.example.com"), service.getAuthorizationServers());
    }

    @Test
    void constructor_withArrayAuthorizationServers() {
        JsonObject mcp = new JsonObject()
                .put("security", new JsonObject()
                        .put("authorizationServers", new JsonArray()
                                .add("https://a.com")
                                .add("https://b.com")));
        WellKnownResourceMetadataService service = new WellKnownResourceMetadataService(mcp);

        assertEquals(List.of("https://a.com", "https://b.com"), service.getAuthorizationServers());
    }

    @Test
    void constructor_withNullAuthorizationServers() {
        JsonObject mcp = new JsonObject()
                .put("security", new JsonObject()
                        .put("authorizationServers", null));
        WellKnownResourceMetadataService service = new WellKnownResourceMetadataService(mcp);

        assertTrue(service.getAuthorizationServers().isEmpty());
    }

    @Test
    void constructor_missingAuthorizationServersKey() {
        JsonObject mcp = new JsonObject().put("security", new JsonObject());
        WellKnownResourceMetadataService service = new WellKnownResourceMetadataService(mcp);

        assertTrue(service.getAuthorizationServers().isEmpty());
    }

    @Test
    void constructor_withInvalidAuthorizationServersType_throwsException() {
        JsonObject mcp = new JsonObject()
                .put("security", new JsonObject()
                        .put("authorizationServers", 123));

        assertThrows(
                IllegalArgumentException.class,
                () -> new WellKnownResourceMetadataService(mcp)
        );
    }

    @Test
    void resolveResourceMetadataPath_withResourceHost() {
        JsonObject mcp = new JsonObject()
                .put("security", new JsonObject().put("resourceHost", "custom.com"));
        WellKnownResourceMetadataService service = new WellKnownResourceMetadataService(mcp);
        when(request.path()).thenReturn("/abc");
        when(request.authority()).thenReturn(HostAndPort.create("ignored.com", -1));

        String result = service.resolveResourceMetadataPath(request);

        assertEquals("https://custom.com/.well-known/oauth-protected-resource/abc", result);
    }

    @Test
    void resolveResourceMetadataPath_withoutResourceHost_usesRequestAuthority() {
        WellKnownResourceMetadataService service = new WellKnownResourceMetadataService(new JsonObject());
        when(request.path()).thenReturn("/x");
        when(request.authority()).thenReturn(HostAndPort.create("host.com", -1));

        String result = service.resolveResourceMetadataPath(request);

        assertEquals("https://host.com/.well-known/oauth-protected-resource/x", result);
    }

    @Test
    void resolveResource_exactPath() {
        JsonObject mcp = new JsonObject().put("security", new JsonObject().put("resourceHost", "h.com"));
        WellKnownResourceMetadataService service = new WellKnownResourceMetadataService(mcp);
        when(request.path()).thenReturn("/.well-known/oauth-protected-resource");
        when(request.authority()).thenReturn(HostAndPort.create("ignored.com", -1));

        String result = service.resolveResource(request);

        assertEquals("https://h.com", result);
    }

    @Test
    void resolveResource_withSubPath() {
        WellKnownResourceMetadataService service = new WellKnownResourceMetadataService(new JsonObject());
        when(request.path()).thenReturn("/.well-known/oauth-protected-resource/data");
        when(request.authority()).thenReturn(HostAndPort.create("example.org", -1));

        String result = service.resolveResource(request);

        assertEquals("https://example.org/data", result);
    }

    @Test
    void resolveResource_invalidPath_throwsException() {
        WellKnownResourceMetadataService service =
                new WellKnownResourceMetadataService(new JsonObject());
        when(request.path()).thenReturn("/invalid");

        assertThrows(IllegalArgumentException.class, () -> service.resolveResource(request));
    }
}
