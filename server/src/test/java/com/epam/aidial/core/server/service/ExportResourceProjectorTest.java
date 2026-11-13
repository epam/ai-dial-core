package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * What one resource of the export becomes: the entity is bound, decrypted for the hint, projected like the admin GET and stripped of its
 * secrets, and a schema loses the upstream keys.
 */
@ExtendWith(MockitoExtension.class)
public class ExportResourceProjectorTest {

    private static final String SECRET = "the-client-secret-8f3a";
    private static final String VERIFIER = "the-code-verifier-value";
    private static final String KEY = "UPSTREAM-KEY-VALUE";
    private static final String EXTRA = "SECRET-EXTRA-DATA-VALUE";

    @Mock
    private ApplicationService applicationService;
    @Mock
    private ToolSetService toolSetService;

    private ExportResourceProjector projector;

    @BeforeEach
    void setUp() {
        projector = new ExportResourceProjector(applicationService, toolSetService);
    }

    private static ResourceDescriptor descriptor(ResourceTypes type, String name) {
        return ResourceDescriptorFactory.fromDecoded(type, "platform", "platform/", name);
    }

    private JsonNode project(ResourceTypes type, String name, String stored) throws IOException {
        ResourceDescriptor descriptor = descriptor(type, name);
        byte[] bytes = projector.projectDeployment(descriptor, projector.bind(descriptor, stored));
        return ProxyUtil.MAPPER.readTree(bytes);
    }

    @Test
    void testApplicationLosesRouteAndUpstreamKeysAndTheSecretsOfItsExternalServices() throws IOException {
        String stored = """
                {"endpoint": "http://a.invalid/chat", "display_name": "A",
                 "routes": {"r": {"paths": ["/x"], "upstreams": [{"endpoint": "http://u.invalid", "key": "%s", "secretExtraData": "%s"}]}},
                 "external_services": {"svc": {"display_name": "S", "auth_settings": {"authentication_type": "OAUTH", "client_id": "cid",
                   "client_secret": "%s", "code_verifier": "%s", "token_endpoint": "https://a.invalid/t"}}}}
                """.formatted(KEY, EXTRA, SECRET, VERIFIER);
        ResourceDescriptor descriptor = descriptor(ResourceTypes.APPLICATION, "a");
        Application entity = (Application) projector.bind(descriptor, stored);

        JsonNode tree = ProxyUtil.MAPPER.readTree(projector.projectDeployment(descriptor, entity));

        verify(applicationService).decryptExternalServiceSecretsForResponse(descriptor, entity);
        String text = tree.toString();
        for (String value : new String[] {KEY, EXTRA, SECRET, VERIFIER}) {
            assertFalse(text.contains(value), value);
        }
        JsonNode auth = tree.at("/external_services/svc/auth_settings");
        assertFalse(auth.has("client_secret") || auth.has("code_verifier"));
        assertEquals("8f3a", auth.get("client_secret_hint").asText());
        assertEquals("cid", auth.get("client_id").asText(), "what is not a secret stays");
        assertEquals("http://u.invalid", tree.at("/routes/r/upstreams/0/endpoint").asText());
        assertFalse(tree.at("/routes/r/upstreams/0").has("key"));
        assertFalse(tree.has("name") || tree.has("status"), "name and status belong to the API projection");
    }

    @Test
    void testToolSetLosesTheSecretsOfItsAuthSettingsAndKeepsTheHint() throws IOException {
        String stored = """
                {"endpoint": "http://t.invalid/mcp", "transport": "HTTP", "display_name": "T",
                 "auth_settings": {"authentication_type": "OAUTH", "client_id": "cid", "client_secret": "%s", "code_verifier": "%s"}}
                """.formatted(SECRET, VERIFIER);
        ResourceDescriptor descriptor = descriptor(ResourceTypes.TOOL_SET, "t");
        ToolSet entity = (ToolSet) projector.bind(descriptor, stored);

        JsonNode tree = ProxyUtil.MAPPER.readTree(projector.projectDeployment(descriptor, entity));

        verify(toolSetService).decryptAuthSettingsForResponse(descriptor, entity);
        assertFalse(tree.toString().contains(SECRET) || tree.toString().contains(VERIFIER));
        assertEquals("8f3a", tree.at("/auth_settings/client_secret_hint").asText());
        assertEquals("http://t.invalid/mcp", tree.get("endpoint").asText());
    }

    @Test
    void testModelLosesTheKeysOfItsUpstreams() throws IOException {
        String stored = """
                {"type": "chat", "endpoint": "http://m.invalid/v1",
                 "upstreams": [{"endpoint": "http://u.invalid", "key": "%s", "secretExtraData": "%s", "weight": 2}]}
                """.formatted(KEY, EXTRA);

        JsonNode tree = project(ResourceTypes.MODEL, "m", stored);

        assertFalse(tree.toString().contains(KEY) || tree.toString().contains(EXTRA));
        assertEquals("http://u.invalid", tree.at("/upstreams/0/endpoint").asText());
        assertEquals(2, tree.at("/upstreams/0/weight").asInt());
        verify(applicationService, never()).decryptExternalServiceSecretsForResponse(any(), any());
    }

    @Test
    void testInterceptorIsTheProjectionOfItsEntity() throws IOException {
        JsonNode tree = project(ResourceTypes.INTERCEPTOR, "i", "{\"endpoint\": \"http://i.invalid\", \"description\": \"d\"}");

        assertEquals("http://i.invalid", tree.get("endpoint").asText());
        assertEquals("d", tree.get("description").asText());
    }

    @Test
    void testEntityOfTheFileConfigurationIsNotDecrypted() throws IOException {
        ResourceDescriptor fileDescriptor = ExportFileEntities.newDescriptor(ResourceTypes.TOOL_SET, "t");
        ToolSet entity = (ToolSet) projector.bind(descriptor(ResourceTypes.TOOL_SET, "t"),
                "{\"endpoint\": \"http://t.invalid/mcp\", \"auth_settings\": {\"authentication_type\": \"OAUTH\", \"client_secret\": \"" + SECRET + "\"}}");

        JsonNode tree = ProxyUtil.MAPPER.readTree(projector.projectDeployment(fileDescriptor, entity));

        verify(toolSetService, never()).decryptAuthSettingsForResponse(any(), any());
        assertFalse(tree.toString().contains(SECRET));
        assertEquals("8f3a", tree.at("/auth_settings/client_secret_hint").asText());
    }

    @Test
    void testDeploymentOfTypeWithoutRedactionRuleIsRefusedWithTheKey() {
        ResourceDescriptor descriptor = descriptor(ResourceTypes.PROMPT, "p");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> projector.projectDeployment(descriptor, new Application()));

        assertEquals("A resource of this type has no rule that removes its secrets (resource=prompts:platform:p)", failure.getMessage());
    }

    @Test
    void testApplicationTypeSchemaLosesTheUpstreamKeysInEverySpelling() throws IOException {
        String stored = """
                {"$id": "https://dial.example/schemas/s", "dial:applicationTypeId": "s",
                 "dial:applicationTypeRoutes": {
                   "a": {"dial:upstreams": [{"dial:endpoint": "http://u1.invalid", "dial:key": "%1$s", "dial:secretExtraData": "%2$s",
                          "dial:interfaces": {"chat": {"dial:key": "%1$s", "dial:secretExtraData": "%2$s", "dial:endpoint": "http://i.invalid"}}}]},
                   "b": {"upstreams": [{"endpoint": "http://u2.invalid", "key": "%1$s", "secretExtraData": "%2$s",
                          "interfaces": {"chat": {"key": "%1$s", "secretExtraData": "%2$s"}}}]}}}
                """.formatted(KEY, EXTRA);
        ResourceDescriptor descriptor = descriptor(ResourceTypes.APP_TYPE_SCHEMA, "blob");

        JsonNode tree = ProxyUtil.MAPPER.readTree(projector.projectSchema(descriptor, stored));

        assertFalse(tree.toString().contains(KEY) || tree.toString().contains(EXTRA), tree.toString());
        assertEquals("http://u1.invalid", tree.at("/dial:applicationTypeRoutes/a/dial:upstreams/0/dial:endpoint").asText());
        assertEquals("http://i.invalid", tree.at("/dial:applicationTypeRoutes/a/dial:upstreams/0/dial:interfaces/chat/dial:endpoint").asText());
        assertEquals("http://u2.invalid", tree.at("/dial:applicationTypeRoutes/b/upstreams/0/endpoint").asText());
        assertEquals("https://dial.example/schemas/s", tree.get("$id").asText());
    }

    @Test
    void testCatalogSchemaIsWrittenAsItIs() throws IOException {
        String stored = "{\"$id\": \"https://dial.example/catalog/c\", \"properties\": {\"key\": {\"type\": \"string\"}}}";

        byte[] bytes = projector.projectSchema(descriptor(ResourceTypes.CATALOG_SCHEMA, "blob"), stored);

        assertEquals(ProxyUtil.MAPPER.readTree(stored), ProxyUtil.MAPPER.readTree(bytes));
    }

    @Test
    void testBodyThatCannotBeBoundFailsWithTheResourceAndNoValue() {
        ResourceDescriptor descriptor = descriptor(ResourceTypes.APPLICATION, "broken");
        String[] bodies = {"not json " + SECRET, "[\"" + SECRET + "\"]", "{\"endpoint\": [\"" + SECRET + "\"]}"};

        for (String body : bodies) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> projector.bind(descriptor, body), body);
            assertTrue(failure.getMessage().endsWith("(resource=application:platform:broken)"), failure.getMessage());
            assertFalse(failure.getMessage().contains(descriptor.getUrl()), "the message names the URL and not the key: " + failure.getMessage());
            assertFalse(failure.getMessage().contains(SECRET), "the message quotes a value: " + failure.getMessage());
            assertNull(failure.getCause(), "a cause could quote a value");
        }
        assertThrows(IllegalStateException.class, () -> projector.projectSchema(descriptor(ResourceTypes.APP_TYPE_SCHEMA, "s"), "[1]"));
    }
}
