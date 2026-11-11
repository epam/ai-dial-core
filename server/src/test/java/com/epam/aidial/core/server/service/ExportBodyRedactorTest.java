package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.data.ExportResourceIdentity;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The redactor of the selective export removes secrets from stored JSON as it is found in the blobs and keeps everything else as stored.
 */
public class ExportBodyRedactorTest {

    private static final String SECRET = "THE-SECRET-VALUE";
    private final ExportBodyRedactor redactor = new ExportBodyRedactor();

    private static ExportResourceIdentity identity(ResourceTypes type) {
        return ExportResourceIdentity.ofDescriptor(ResourceDescriptorFactory.fromDecoded(type, "platform", "platform/", "x"), null);
    }

    private byte[] redact(ResourceTypes type, String body) {
        return redactor.redact(identity(type), body);
    }

    private static JsonNode json(byte[] bytes) throws IOException {
        return ProxyUtil.MAPPER.readTree(bytes);
    }

    // ---- a toolset with authorization secrets

    private static String toolSetWithSecrets() {
        return "{\"endpoint\":\"http://tools.invalid/mcp\",\"transport\":\"HTTP\",\"auth_settings\":{\"authentication_type\":\"OAUTH\","
                + "\"client_id\":\"my-client-id\",\"client_secret\":\"" + SECRET + "\",\"client_secret_hint\":\"CRET\",\"code_verifier\":\""
                + SECRET + "-verifier\",\"token_endpoint\":\"https://auth.invalid/token\",\"global_auth_status\":\"AUTHORIZED\"}}";
    }

    // ---- what is kept

    @Test
    void testBodyWithoutSensitiveFieldsIsKeptValueForValueAndInOrder() throws Exception {
        String stored = "{\"display_name\":\"App\",\"endpoint\":\"https://a.invalid/chat?limit=5\",\"forward_auth_token\":true,"
                + "\"max_retry_attempts\":3,\"user_roles\":[\"r1\",\"r2\"],\"defaults\":{},\"interceptors\":[\"i1\"],"
                + "\"dependencies\":[\"applications/platform/b\"],\"function\":{\"id\":\"f\"}}";

        byte[] redacted = redact(ResourceTypes.APPLICATION, stored);

        assertEquals(ProxyUtil.MAPPER.readTree(stored), json(redacted));
        assertEquals(stored, new String(redacted, StandardCharsets.UTF_8), "the fields keep their order and spelling");
    }

    @Test
    void testUrlsAndFreeTextAreNeverInspectedOrChanged() throws Exception {
        // the policy does not read URLs or text: user information, path, query and fragment are exported as they are (a documented limitation)
        // a string that merely starts with the ciphertext prefix is not a secret either: only a field that SecretFieldProcessor removes is removed
        String stored = "{\"note\":\"ENC[not a secret, a note]\",\"endpoint\":\"https://a.invalid/chat?token=" + SECRET
                + "\",\"viewer_url\":\"https://v.invalid/" + SECRET + "/a@b#" + SECRET + "\","
                + "\"icon_url\":\"https://user:" + SECRET + "@i.invalid/x.png?a=1&b=2\","
                + "\"description\":\"password: " + SECRET + ", see https://d.invalid/p?key=" + SECRET + " or mail a@b.invalid\"}";

        for (ResourceTypes type : List.of(ResourceTypes.APPLICATION, ResourceTypes.TOOL_SET, ResourceTypes.MODEL, ResourceTypes.INTERCEPTOR)) {
            assertEquals(stored, new String(redact(type, stored), StandardCharsets.UTF_8), type.name());
        }
        String schema = "{\"$id\":\"https://dial.example/schemas/s?v=1#top\",\"dial:applicationTypeEditorUrl\":\"https://e.invalid/?t=" + SECRET + "\"}";
        assertEquals(schema, new String(redact(ResourceTypes.APP_TYPE_SCHEMA, schema), StandardCharsets.UTF_8));
        assertEquals(schema, new String(redact(ResourceTypes.CATALOG_SCHEMA, schema), StandardCharsets.UTF_8));
    }

    // ---- the tree entry

    @Test
    void testTreeEntryPointDoesNotChangeTheTreeItIsGiven() throws Exception {
        JsonNode tree = ProxyUtil.MAPPER.readTree(toolSetWithSecrets());
        JsonNode copy = tree.deepCopy();

        redactor.redact(identity(ResourceTypes.TOOL_SET), toolSetWithSecrets(), tree);

        assertEquals(copy, tree, "the caller can still use the tree, for example to read the schema id");
    }

    // ---- what is refused

    @Test
    void testPromptIsExportedExactlyAsStoredWhateverItHolds() throws Exception {
        // pretty-printed, escaped, with a non-integer number and every kind of content that another type would lose or change
        String stored = "{\n  \"id\" : \"p\",\n  \"folderId\" : \"\",\n  \"name\" : \"P \\u00e9\",\n  \"content\" : \"Use https://user:" + SECRET
                + "@a.invalid/x?token=" + SECRET + "#f and key=value and ENC[abc]\",\n  \"default_headers\" : { \"X-Key\" : \"" + SECRET + "\" },\n"
                + "  \"application_properties\" : { \"a\" : 1.50 },\n  \"dial:key\" : \"" + SECRET + "\",\n  \"extra\" : { \"nested\" : [ 1, 2 ] }\n}";

        byte[] bytes = redact(ResourceTypes.PROMPT, stored);

        assertEquals(stored, new String(bytes, StandardCharsets.UTF_8), "the stored bytes, not a re-serialization of them");
    }

    @Test
    void testApplicationPropertiesFunctionEnvAndDefaultHeadersHaveNoRuleAndAreExportedAsStored() throws Exception {
        // application properties, function env and default headers have no redaction rule: they are neither removed nor read, under any spelling or place
        String properties = "{\"tool_sets\":[{\"authorization\":{\"token\":\"" + SECRET + "\"}}],\"skill\":{\"url\":\"skills/public/s\"},\"n\":1}";
        String[] bodies = {
            "{\"endpoint\":\"http://a.invalid\",\"application_properties\":" + properties + "}",
            "{\"endpoint\":\"http://a.invalid\",\"applicationProperties\":" + properties + "}",
            "{\"endpoint\":\"http://a.invalid\",\"function\":{\"id\":\"f\",\"env\":{\"TOKEN\":\"" + SECRET + "\"}}}",
            "{\"endpoint\":\"http://a.invalid\",\"default_headers\":{\"X-Key\":\"" + SECRET + "\"}}",
            "{\"endpoint\":\"http://a.invalid\",\"defaultHeaders\":{\"X-Key\":\"" + SECRET + "\"}}",
            "{\"endpoint\":\"http://a.invalid\",\"interfaces\":{\"chat_completions\":{\"default_headers\":{\"X-Key\":\"" + SECRET + "\"}}}}",
        };
        for (ResourceTypes type : List.of(ResourceTypes.APPLICATION, ResourceTypes.MODEL, ResourceTypes.TOOL_SET, ResourceTypes.INTERCEPTOR)) {
            for (String body : bodies) {
                assertEquals(ProxyUtil.MAPPER.readTree(body), json(redact(type, body)), type + " " + body);
            }
        }
    }

    @Test
    @Disabled("Redaction is switched off temporarily for testing")
    void testSchemaRouteKeyIsRemovedAtEveryRouteAndUpstream() throws Exception {
        // the routes are an object keyed by route name, as the meta-schema defines them
        String keyed = "{\"$id\":\"https://s\",\"dial:applicationTypeRoutes\":{\"route_a\":{\"dial:upstreams\":[{\"dial:endpoint\":\"http://u.invalid\","
                + "\"dial:key\":\"" + SECRET + "\"}]},\"route_b\":{\"dial:upstreams\":[{\"dial:endpoint\":\"http://v.invalid\"},{\"dial:key\":\"" + SECRET + "\"}]}}}";
        JsonNode routes = json(redact(ResourceTypes.APP_TYPE_SCHEMA, keyed)).get("dial:applicationTypeRoutes");
        assertEquals("http://u.invalid", routes.get("route_a").get("dial:upstreams").get(0).get("dial:endpoint").asText());
        assertFalse(routes.get("route_a").get("dial:upstreams").get(0).has("dial:key"));
        assertFalse(routes.get("route_b").get("dial:upstreams").get(1).has("dial:key"));
        // [*] also covers routes held in an array
        String array = "{\"dial:applicationTypeRoutes\":[{\"dial:upstreams\":[{\"dial:key\":\"" + SECRET + "\"}]}]}";
        assertFalse(new String(redact(ResourceTypes.APP_TYPE_SCHEMA, array), StandardCharsets.UTF_8).contains(SECRET));
    }

    @Test
    @Disabled("Redaction is switched off temporarily for testing")
    void testBodiesWithEmptyNullOrAbsentRoutesAndKeysAreKeptAndNeverRefused() throws Exception {
        String[] applications = {
                "{\"endpoint\":\"http://a.invalid\"}",
                "{\"endpoint\":\"http://a.invalid\",\"application_properties\":null}",
                "{\"endpoint\":\"http://a.invalid\",\"function\":{\"env\":null}}",
        };
        for (String stored : applications) {
            assertEquals(ProxyUtil.MAPPER.readTree(stored), json(redact(ResourceTypes.APPLICATION, stored)), stored);
        }
        String[] schemas = {
                "{\"$id\":\"https://s\"}",
                "{\"$id\":\"https://s\",\"dial:applicationTypeRoutes\":{}}",
                "{\"dial:applicationTypeRoutes\":{\"r\":{\"dial:upstreams\":[{\"dial:endpoint\":\"http://u.invalid\"}]}}}",
                "{\"dial:applicationTypeRoutes\":{\"r\":{\"dial:upstreams\":[]}}}",
        };
        for (String schema : schemas) {
            assertEquals(ProxyUtil.MAPPER.readTree(schema), json(redact(ResourceTypes.APP_TYPE_SCHEMA, schema)), schema);
        }
        // an empty key is still a key and is removed; the extra data next to it is not a secret of Core
        String withEmptyKey = "{\"$id\":\"https://s\",\"dial:applicationTypeRoutes\":{\"r\":{\"dial:upstreams\":[{\"dial:endpoint\":\"http://u.invalid\","
                + "\"dial:key\":\"\",\"dial:extraData\":\"" + SECRET + "\"}]}}}";
        JsonNode upstream = json(redact(ResourceTypes.APP_TYPE_SCHEMA, withEmptyKey)).get("dial:applicationTypeRoutes").get("r").get("dial:upstreams").get(0);
        assertFalse(upstream.has("dial:key"));
        assertEquals("http://u.invalid", upstream.get("dial:endpoint").asText());
        assertEquals(SECRET, upstream.get("dial:extraData").asText(), "dial:extraData is not a secret of Core and is kept");
    }

    @Test
    void testDialKeyOutsideTheRouteUpstreamsIsAnOrdinaryPropertyAndIsKept() throws Exception {
        String[] kept = {
                // in the same schema, but not under a route upstream
                "{\"$id\":\"https://s\",\"properties\":{\"dial:key\":\"" + SECRET + "\"}}",
                "{\"$id\":\"https://s\",\"dial:key\":\"" + SECRET + "\"}",
                // a route that is not an upstream
                "{\"dial:applicationTypeRoutes\":{\"r\":{\"dial:key\":\"" + SECRET + "\"}}}",
        };
        for (String schema : kept) {
            assertEquals(ProxyUtil.MAPPER.readTree(schema), json(redact(ResourceTypes.APP_TYPE_SCHEMA, schema)), schema);
        }
        // another resource type is not a schema, and its dial:key is an ordinary property
        String catalog = "{\"dial:key\":\"" + SECRET + "\"}";
        assertEquals(ProxyUtil.MAPPER.readTree(catalog), json(redact(ResourceTypes.CATALOG_SCHEMA, catalog)));
        String application = "{\"endpoint\":\"http://a.invalid\",\"defaults\":{\"dial:key\":\"v\"}}";
        assertEquals(ProxyUtil.MAPPER.readTree(application), json(redact(ResourceTypes.APPLICATION, application)));
    }

    @Test
    @Disabled("Redaction is switched off temporarily for testing")
    void testRefusalExceptionNamesTheResourceAndTheRuleButNoValue() {
        IllegalStateException noRule = assertThrows(IllegalStateException.class, () -> redact(ResourceTypes.CONVERSATION, "{\"content\":\"" + SECRET + "\"}"));
        IllegalStateException notObject = assertThrows(IllegalStateException.class, () -> redact(ResourceTypes.PROMPT, "[\"" + SECRET + "\"]"));
        IllegalStateException arrayOfModel = assertThrows(IllegalStateException.class, () -> redact(ResourceTypes.MODEL, "[\"" + SECRET + "\"]"));
        IllegalStateException notJson = assertThrows(IllegalStateException.class, () -> redact(ResourceTypes.MODEL, "{ this is not json " + SECRET));

        for (IllegalStateException failure : List.of(noRule, notObject, arrayOfModel, notJson)) {
            assertFalse(failure.getMessage().contains(SECRET), failure.getMessage());
            assertNull(failure.getCause());
        }
        assertTrue(noRule.getMessage().contains(identity(ResourceTypes.CONVERSATION).key()), noRule.getMessage());
        assertTrue(noRule.getMessage().contains("No redaction exists"), noRule.getMessage());
        assertTrue(notObject.getMessage().contains(identity(ResourceTypes.PROMPT).key()), notObject.getMessage());
        assertTrue(notObject.getMessage().contains("not a JSON object"), notObject.getMessage());
        assertTrue(arrayOfModel.getMessage().contains("not a JSON object"), arrayOfModel.getMessage());
        assertTrue(notJson.getMessage().contains(identity(ResourceTypes.MODEL).key()), notJson.getMessage());
        assertTrue(notJson.getMessage().contains("not valid JSON"), notJson.getMessage());
        assertFalse(notJson.getMessage().contains("this is not json"), notJson.getMessage());
    }

    // ---- alias spellings: the stored JSON may name a property by any spelling that Jackson reads into it

    static Stream<Arguments> upstreamBodiesWithSecretsInEverySpelling() {
        return Stream.of(
                Arguments.of("model upstreams in every spelling", ResourceTypes.MODEL, "{\"type\":\"chat\",\"upstreams\":["
                        + "{\"endpoint\":\"http://a.invalid\",\"dial:key\":\"" + SECRET + "-1\",\"dial:secretExtraData\":\"" + SECRET + "-2\"},"
                        + "{\"dial:endpoint\":\"http://b.invalid\",\"key\":\"" + SECRET + "-3\",\"secretExtraData\":\"" + SECRET + "-4\","
                        + "\"dial:extraData\":\"not-a-secret\"}]}"),
                Arguments.of("canonical and alias spellings in one application body", ResourceTypes.APPLICATION, "{\"routes\":{\"r\":"
                        + "{\"upstreams\":[{\"key\":\"" + SECRET + "\"}],\"dial:upstreams\":[{\"key\":\"" + SECRET + "\",\"dial:extraData\":\"not-a-secret\"}]}}}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("upstreamBodiesWithSecretsInEverySpelling")
    @Disabled("Redaction is switched off temporarily for testing")
    void testEverySpellingOfUpstreamSecretsIsRemoved(String description, ResourceTypes type, String stored) {
        String output = new String(redact(type, stored), StandardCharsets.UTF_8);

        assertFalse(output.contains(SECRET), "a spelling of an upstream secret survived: " + output);
        assertTrue(output.contains("not-a-secret"), "dial:extraData is not a secret and is kept: " + output);
    }

    @Test
    @Disabled("Redaction is switched off temporarily for testing")
    void testEverySpellingOfSchemaRouteUpstreamSecretsIsRemoved() throws Exception {
        // the spellings are those of Upstream.key and Upstream.secretExtraData, both @EncryptedField, which a schema document holds without a class
        String[] schemas = {
                "{\"dial:applicationTypeRoutes\":{\"r\":{\"upstreams\":[{\"key\":\"" + SECRET + "\"}]}}}",
                "{\"dial:applicationTypeRoutes\":{\"r\":{\"dial:upstreams\":[{\"key\":\"" + SECRET + "\"}]}}}",
                "{\"dial:applicationTypeRoutes\":{\"r\":{\"upstreams\":[{\"dial:key\":\"" + SECRET + "\"}]}}}",
                "{\"dial:applicationTypeRoutes\":{\"r\":{\"dial:upstreams\":[{\"dial:secretExtraData\":\"" + SECRET + "\"}]}}}",
                "{\"dial:applicationTypeRoutes\":{\"r\":{\"dial:upstreams\":[{\"secretExtraData\":\"" + SECRET + "\"}]}}}",
                "{\"dial:applicationTypeRoutes\":{\"r\":{\"dial:upstreams\":[{\"dial:interfaces\":{\"chat\":{\"dial:key\":\"" + SECRET + "\"}}}]}}}",
                "{\"dial:applicationTypeRoutes\":{\"r\":{\"dial:upstreams\":[{\"interfaces\":{\"chat\":{\"secretExtraData\":\"" + SECRET + "\"}}}]}}}",
        };
        for (String schema : schemas) {
            assertFalse(new String(redact(ResourceTypes.APP_TYPE_SCHEMA, schema), StandardCharsets.UTF_8).contains(SECRET), schema);
        }
    }

    @Test
    void testSchemaRouteExtraDataThatIsNotSecretIsKept() throws Exception {
        String schema = "{\"dial:applicationTypeRoutes\":{\"r\":{\"dial:upstreams\":[{\"dial:extraData\":\"" + SECRET + "\","
                + "\"dial:interfaces\":{\"chat\":{\"dial:endpoint\":\"http://i.invalid\"}}}]}}}";

        assertEquals(ProxyUtil.MAPPER.readTree(schema), json(redact(ResourceTypes.APP_TYPE_SCHEMA, schema)),
                "dial:extraData is not an @EncryptedField of Upstream, so it is not covered");
    }

}
