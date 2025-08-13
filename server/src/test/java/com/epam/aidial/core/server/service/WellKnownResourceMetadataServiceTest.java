package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.data.wellknown.ResourceMetadata;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.HostAndPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class WellKnownResourceMetadataServiceTest {

    @Mock
    private HttpServerRequest request;

    @Test
    void resolveResourceMetadataPath_withResourceHost() {
        JsonObject mcp = new JsonObject()
                .put("security", new JsonObject()
                        .put("authorizationServers", "https://auth.example.com")
                        .put("resourceHost", "custom.com"));
        WellKnownResourceMetadataService service = new WellKnownResourceMetadataService(mcp);

        when(request.path()).thenReturn("/abc");
        when(request.authority()).thenReturn(HostAndPort.create("ignored.com", -1));

        Optional<String> pathOptional = service.resolveResourceMetadataPath(request);
        assertTrue(pathOptional.isPresent());
        assertEquals("https://custom.com/.well-known/oauth-protected-resource/abc", pathOptional.get());
    }

    @Test
    void resolveResourceMetadataPath_withoutResourceHost_usesRequestAuthority() {
        JsonObject mcp = new JsonObject()
                .put("security", new JsonObject()
                        .put("authorizationServers", "https://auth.example.com"));
        WellKnownResourceMetadataService service = new WellKnownResourceMetadataService(mcp);

        when(request.path()).thenReturn("/x");
        when(request.authority()).thenReturn(HostAndPort.create("host.com", -1));

        Optional<String> pathOptional = service.resolveResourceMetadataPath(request);
        assertTrue(pathOptional.isPresent());
        assertEquals("https://host.com/.well-known/oauth-protected-resource/x", pathOptional.get());
    }

    @Test
    void resolveResourceMetadataPath_returnsEmptyOptionalWhenDisabled() {
        WellKnownResourceMetadataService service =
                new WellKnownResourceMetadataService(new JsonObject()); // no auth servers

        Optional<String> pathOptional = service.resolveResourceMetadataPath(request);

        assertTrue(pathOptional.isEmpty());
    }

    @Test
    void resolveResourceMetadata_exactPath_setsRootResource() {
        JsonObject mcp = new JsonObject()
                .put("security", new JsonObject()
                        .put("authorizationServers", "https://auth.example.com")
                        .put("resourceHost", "custom.com"));
        WellKnownResourceMetadataService service = new WellKnownResourceMetadataService(mcp);

        when(request.path()).thenReturn("/.well-known/oauth-protected-resource");
        when(request.authority()).thenReturn(HostAndPort.create("ignored.com", -1));

        Optional<ResourceMetadata> metadataOptional = service.resolveResourceMetadata(request);
        assertTrue(metadataOptional.isPresent());
        ResourceMetadata metadata = metadataOptional.get();
        assertEquals("https://custom.com", metadata.getResource());
        assertEquals(List.of("https://auth.example.com"), metadata.getAuthorizationServers());
    }

    @Test
    void resolveResourceMetadata_withSubPath_setsFullResource() {
        JsonObject mcp = new JsonObject()
                .put("security", new JsonObject()
                        .put("authorizationServers", new JsonArray()
                                .add("https://a.auth.example.com")
                                .add("https://b.auth.example.com")));
        WellKnownResourceMetadataService service = new WellKnownResourceMetadataService(mcp);

        when(request.path()).thenReturn("/.well-known/oauth-protected-resource/data");
        when(request.authority()).thenReturn(HostAndPort.create("host.org", -1));

        Optional<ResourceMetadata> metadataOptional = service.resolveResourceMetadata(request);
        assertTrue(metadataOptional.isPresent());
        ResourceMetadata metadata = metadataOptional.get();
        assertEquals("https://host.org/data", metadata.getResource());
        assertEquals(List.of("https://a.auth.example.com", "https://b.auth.example.com"),
                metadata.getAuthorizationServers());
    }

    @Test
    void resolveResourceMetadata_returnsEmptyOptionalWhenDisabled() {
        WellKnownResourceMetadataService service =
                new WellKnownResourceMetadataService(new JsonObject()); // no auth servers

        Optional<ResourceMetadata> metadataOptional = service.resolveResourceMetadata(request);

        assertTrue(metadataOptional.isEmpty());
    }

    @Test
    void resolveResourceMetadata_invalidPath_throwsException() {
        JsonObject mcp = new JsonObject()
                .put("security", new JsonObject()
                        .put("authorizationServers", "https://auth.example.com"));
        WellKnownResourceMetadataService service = new WellKnownResourceMetadataService(mcp);

        when(request.path()).thenReturn("/invalid");

        assertThrows(IllegalArgumentException.class,
                () -> service.resolveResourceMetadata(request));
    }
}
