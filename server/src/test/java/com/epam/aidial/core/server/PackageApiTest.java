package com.epam.aidial.core.server;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.epam.aidial.core.server.config.ConfigPostProcessor;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Admin API of packages, {@code /v1/packages/platform/<name>} and the {@code Package} manifest kind, and policies that
 * name a package in their {@code object}.
 */
public class PackageApiTest extends ResourceBaseTest {

    private static final String PACKAGES = "/v1/packages/platform/";
    private static final String POLICIES = "/v1/policies/platform/";

    private static String policy(int priority, String object) {
        return """
                { "priority": %d, "kind": "budget", "quantity": "cost",
                  "object": %s, "per": ["subject"], "cost": { "month": 100 } }
                """.formatted(priority, object);
    }

    private static String policyPackage(String deployments) {
        return """
                { "deployments": %s, "description": "Engineering" }
                """.formatted(deployments);
    }

    private Response put(String url, String body) {
        return send(HttpMethod.PUT, url, null, body, "authorization", "admin");
    }

    private Response get(String url) {
        return send(HttpMethod.GET, url, null, "", "authorization", "admin");
    }

    private Response delete(String url) {
        return send(HttpMethod.DELETE, url, null, "", "authorization", "admin");
    }

    private Response post(String url, String body) {
        return send(HttpMethod.POST, url, null, body, "authorization", "admin");
    }

    @SneakyThrows
    private static JsonNode json(Response response) {
        return ProxyUtil.MAPPER.readTree(response.body());
    }

    private static void verifyRejected(Response response, int status, String message) {
        assertEquals(status, response.status(), () -> "Actual response body: " + response.body());
        assertTrue(response.body().contains(message), () -> "Expected '" + message + "' in: " + response.body());
    }

    @Test
    void testPackageIsWrittenAndReadBack() {
        Response created = put(PACKAGES + "engineering", policyPackage("[\"weak\", \"medium\", \"strong\"]"));
        verify(created, 200);
        assertNotNull(created.headers().get("etag"));

        JsonNode stored = json(get(PACKAGES + "engineering"));
        assertEquals("[\"weak\",\"medium\",\"strong\"]", stored.get("deployments").toString());
        assertEquals("Engineering", stored.get("description").asText());
        assertEquals("engineering", stored.get("name").asText());
        assertEquals("valid", stored.get("status").asText());
        assertTrue(dial.getProxy().getConfigStore().get().getPackages().containsKey("engineering"));
    }

    @Test
    void testNonAdminCannotWritePackages() {
        verify(send(HttpMethod.PUT, PACKAGES + "denied", null, policyPackage("[\"weak\"]")), 403);
        verify(send(HttpMethod.GET, PACKAGES + "denied"), 403);
        verify(get(PACKAGES + "denied"), 404);
    }

    @Test
    void testNestedPackageWildcardAndEmptyListAreRejected() {
        verifyRejected(put(PACKAGES + "p", policyPackage("[\"@engineering\"]")), 400, "a package cannot include another package");
        verifyRejected(put(PACKAGES + "p", policyPackage("[\"*\"]")), 400, "must not hold \"*\"");
        verifyRejected(put(PACKAGES + "p", policyPackage("[]")), 400, "non-empty list");
        verifyRejected(put(PACKAGES + "p", "{ \"description\": \"no members\" }"), 400, "non-empty list");
        verify(get(PACKAGES + "p"), 404);
    }

    @Test
    void testUnknownFieldIsRejected() {
        verifyRejected(put(PACKAGES + "p", "{ \"deployments\": [\"weak\"], \"owner\": \"x\" }"), 400, "unrecognized field");
        verify(get(PACKAGES + "p"), 404);
    }

    @Test
    void testNameWithFolderIsRejected() {
        verify(put(PACKAGES + "team/p", policyPackage("[\"weak\"]")), 400);
        verify(get(PACKAGES + "team/p"), 404);
    }

    @Test
    void testDanglingMemberIsAcceptedAndLogged() {
        Logger logger = (Logger) LoggerFactory.getLogger(ConfigPostProcessor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            verify(put(PACKAGES + "learning", policyPackage("[\"chat-gpt-35-turbo\", \"retired-model\"]")), 200);
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals("valid", json(get(PACKAGES + "learning")).get("status").asText());
        assertTrue(appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.equals("Package 'learning' names 'retired-model', which is not a deployment")));
        assertFalse(appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("'chat-gpt-35-turbo'")));
    }

    @Test
    void testPolicyNamesPackageAndReadsItBackAsWritten() {
        verify(put(PACKAGES + "engineering", policyPackage("[\"weak\"]")), 200);

        verify(put(POLICIES + "single-ref", policy(10, "\"@engineering\"")), 200);
        assertEquals("\"@engineering\"", json(get(POLICIES + "single-ref")).get("object").toString());

        verify(put(POLICIES + "mixed-ref", policy(20, "[\"gpt-6-luna\", \"@engineering\"]")), 200);
        assertEquals("[\"@engineering\",\"gpt-6-luna\"]", json(get(POLICIES + "mixed-ref")).get("object").toString());
    }

    @Test
    void testReservedPackagesFieldAndWildcardInListAreRejected() {
        verifyRejected(put(POLICIES + "reserved", policy(10, "\"*\"").replace("\"per\"", "\"packages\": [\"engineering\"], \"per\"")),
                422, "'packages' is not supported yet");
        verify(put(POLICIES + "wildcard-in-list", policy(10, "[\"@engineering\", \"*\"]")), 400);
        verify(get(POLICIES + "wildcard-in-list"), 404);
    }

    @Test
    void testPolicyNamingMissingPackageIsRejected() {
        verifyRejected(put(POLICIES + "dangling-ref", policy(10, "\"@nope\"")), 422, "Package 'nope' does not exist");
        verify(get(POLICIES + "dangling-ref"), 404);
    }

    @Test
    void testReferencedPackageIsNotDeleted() {
        verify(put(PACKAGES + "engineering", policyPackage("[\"weak\"]")), 200);
        verify(put(POLICIES + "IMF/imf-eng-1000", policy(10, "\"@engineering\"").replace("\"per\"", "\"costObject\": \"IMF\", \"per\"")), 200);

        verifyRejected(delete(PACKAGES + "engineering"), 409, "IMF/imf-eng-1000");
        verify(get(PACKAGES + "engineering"), 200);

        verify(delete(POLICIES + "IMF/imf-eng-1000"), 204);
        verify(delete(PACKAGES + "engineering"), 204);
        verify(get(PACKAGES + "engineering"), 404);
        assertFalse(dial.getProxy().getConfigStore().get().getPackages().containsKey("engineering"));
    }

    @Test
    void testUnreferencedPackageIsDeleted() {
        verify(put(PACKAGES + "learning", policyPackage("[\"weak\"]")), 200);

        verify(delete(PACKAGES + "learning"), 204);
        verify(delete(PACKAGES + "learning"), 404);
    }

    @Test
    void testPackageIsAppliedBeforeThePolicyNamingIt() {
        String body = """
                { "manifests": [
                  { "kind": "Policy", "name": "policies/platform/batch-eng", "spec": %s },
                  { "kind": "Package", "name": "packages/platform/batch-engineering", "spec": %s }
                ] }
                """.formatted(policy(10, "\"@batch-engineering\""), policyPackage("[\"weak\", \"strong\"]"));

        Response validated = post("/v1/admin/validate", body);
        verify(validated, 200);
        assertEquals(2, json(validated).get("valid").asInt());

        Response applied = post("/v1/admin/apply", body);
        verify(applied, 200);
        JsonNode results = json(applied).get("results");
        assertEquals("packages/platform/batch-engineering", results.get(0).get("entityId").asText());
        assertEquals("APPLIED", results.get(0).get("status").asText());
        assertEquals("APPLIED", results.get(1).get("status").asText());
        assertEquals("\"@batch-engineering\"", json(get(POLICIES + "batch-eng")).get("object").toString());
        verify(get(PACKAGES + "batch-engineering"), 200);
    }

    @Test
    void testPackageManifestWithFolderOrBadBodyFails() {
        String body = """
                { "precheck": false, "manifests": [
                  { "kind": "Package", "name": "packages/platform/team/p", "spec": { "deployments": ["weak"] } },
                  { "kind": "Package", "name": "packages/platform/nested", "spec": { "deployments": ["@other"] } }
                ] }
                """;

        Response applied = post("/v1/admin/apply", body);
        verify(applied, 200);
        JsonNode results = json(applied).get("results");
        assertEquals("FAILED", results.get(0).get("status").asText());
        assertEquals("FAILED", results.get(1).get("status").asText());
        assertTrue(results.get(1).get("error").asText().contains("a package cannot include another package"));
        verify(get(PACKAGES + "nested"), 404);
    }

    @Test
    void testPolicyNamingMissingPackageFailsInBatch() {
        String body = """
                { "manifests": [ { "kind": "Policy", "name": "policies/platform/batch-dangling", "spec": %s } ] }
                """.formatted(policy(10, "\"@nope\""));

        Response validated = post("/v1/admin/validate", body);
        verify(validated, 422);
        assertTrue(json(validated).get("results").get(0).get("error").asText().contains("Package 'nope' does not exist"));
    }
}
