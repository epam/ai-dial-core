package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.ExternalService;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The projection that the config API and the export share: what leaves the system for an entity. */
public class EntityProjectionTest {

    private static ResourceAuthSettings authSettings(String clientSecret) {
        ResourceAuthSettings settings = new ResourceAuthSettings();
        settings.setClientId("cid");
        settings.setClientSecret(clientSecret);
        settings.setCodeVerifier("verifier-value");
        return settings;
    }

    private static ObjectNode parse(String json) throws IOException {
        return (ObjectNode) ProxyUtil.MAPPER.readTree(json);
    }

    @Test
    void testProjectionDropsEveryWriteOnlySecretAndIsCopy() {
        Upstream upstream = new Upstream();
        upstream.setEndpoint("http://u.invalid");
        upstream.setKey("UPSTREAM-KEY");
        upstream.setSecretExtraData("EXTRA");
        Model model = new Model();
        model.setEndpoint("http://m.invalid");
        model.setUpstreams(List.of(upstream));

        ObjectNode tree = EntityProjection.project(model);

        assertFalse(tree.toString().contains("UPSTREAM-KEY") || tree.toString().contains("EXTRA"), tree.toString());
        assertEquals("http://u.invalid", tree.at("/upstreams/0/endpoint").asText());
        tree.removeAll();
        assertEquals("UPSTREAM-KEY", upstream.getKey(), "the entity is not changed by the projection");
    }

    @Test
    void testToolSetSecretsAreRemovedAndTheHintIsTheTailOfLongSecretForAdmin() {
        ToolSet toolSet = new ToolSet();
        toolSet.setAuthSettings(authSettings("0123456789abcdef"));

        ObjectNode tree = EntityProjection.redactAuthSettingsSecrets(EntityProjection.project(toolSet), true);

        JsonNode auth = tree.get("auth_settings");
        assertFalse(auth.has("client_secret") || auth.has("code_verifier"));
        assertEquals("cdef", auth.get("client_secret_hint").asText());
        assertEquals("cid", auth.get("client_id").asText());
        assertEquals("0123456789abcdef", toolSet.getAuthSettings().getClientSecret(), "the entity is not changed");
    }

    @Test
    void testHintLengthFollowsTheLengthOfTheSecretAndIsAbsentWithoutAnAdmin() {
        ToolSet medium = new ToolSet();
        medium.setAuthSettings(authSettings("12345678"));
        ToolSet tiny = new ToolSet();
        tiny.setAuthSettings(authSettings("1234567"));
        ToolSet long12 = new ToolSet();
        long12.setAuthSettings(authSettings("123456789012"));

        assertEquals("78", EntityProjection.redactAuthSettingsSecrets(EntityProjection.project(medium), true)
                .at("/auth_settings/client_secret_hint").asText());
        assertNull(EntityProjection.redactAuthSettingsSecrets(EntityProjection.project(tiny), true).at("/auth_settings/client_secret_hint").textValue());
        assertEquals("9012", EntityProjection.redactAuthSettingsSecrets(EntityProjection.project(long12), true)
                .at("/auth_settings/client_secret_hint").asText());
        ObjectNode forOthers = EntityProjection.redactAuthSettingsSecrets(EntityProjection.project(long12), false);
        assertFalse(forOthers.at("/auth_settings").has("client_secret_hint"));
        assertFalse(forOthers.at("/auth_settings").has("client_secret"));
    }

    @Test
    void testHintOfTheProjectedNodeIsNeverCopiedThrough() throws IOException {
        ObjectNode tree = parse("{\"auth_settings\": {\"client_secret_hint\": \"fake\", \"client_id\": \"c\"}}");

        EntityProjection.redactAuthSettingsSecrets(tree, true);

        assertFalse(tree.get("auth_settings").has("client_secret_hint"), "the hint is derived from the secret or absent");
    }

    @Test
    void testExternalServicesOfAnApplicationAreRedactedOneByOne() {
        Application application = new Application();
        application.setEndpoint("http://a.invalid");
        ExternalService one = new ExternalService();
        one.setAuthSettings(authSettings("secret-one-value-1111"));
        ExternalService two = new ExternalService();
        two.setAuthSettings(authSettings("secret-two-value-2222"));
        application.setExternalServices(new LinkedHashMap<>(Map.of("one", one, "two", two)));

        ObjectNode tree = EntityProjection.redactExternalServiceSecrets(EntityProjection.project(application), true);

        assertEquals("1111", tree.at("/external_services/one/auth_settings/client_secret_hint").asText());
        assertEquals("2222", tree.at("/external_services/two/auth_settings/client_secret_hint").asText());
        assertFalse(tree.toString().contains("secret-one") || tree.toString().contains("secret-two") || tree.toString().contains("verifier-value"));
    }

    @Test
    void testShapesWithoutAuthSettingsAreLeftAlone() throws IOException {
        ObjectNode none = parse("{\"endpoint\": \"e\"}");
        ObjectNode scalar = parse("{\"auth_settings\": \"x\", \"external_services\": [1]}");

        assertEquals(parse("{\"endpoint\": \"e\"}"), EntityProjection.redactAuthSettingsSecrets(none, true));
        assertEquals(parse("{\"endpoint\": \"e\"}"), EntityProjection.redactExternalServiceSecrets(none, true));
        EntityProjection.redactAuthSettingsSecrets(scalar, true);
        EntityProjection.redactExternalServiceSecrets(scalar, true);
        assertEquals(parse("{\"auth_settings\": \"x\", \"external_services\": [1]}"), scalar);
    }

    @Test
    void testSchemaUpstreamKeysAreRemovedAtTheRoutesOnlyAndTheRestStays() throws IOException {
        ObjectNode schema = parse("""
                {"key": "top-level-stays", "dial:key": "top-stays",
                 "dial:applicationTypeRoutes": [{"dial:upstreams": [{"dial:key": "K", "key": "K", "dial:secretExtraData": "E", "secretExtraData": "E",
                    "dial:endpoint": "http://u"}], "key": "route-level-stays"}]}
                """);

        EntityProjection.removeSchemaUpstreamKeys(schema);

        assertTrue(schema.toString().contains("top-level-stays") && schema.toString().contains("top-stays") && schema.toString().contains("route-level-stays"));
        assertEquals(parse("{\"dial:endpoint\": \"http://u\"}"), schema.at("/dial:applicationTypeRoutes/0/dial:upstreams/0"));
    }
}
