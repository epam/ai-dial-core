package com.epam.aidial.core.server;

import ch.qos.logback.classic.Level;
import com.epam.aidial.core.server.DependencyListApiSupport.Logs;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code POST /v1/ops/dependency/list} end to end through the real server: the route, the shape of the answer for an explicit admin, a root
 * that is not usable, and the findings that never fail the request. The walk itself is tested in {@code DependencyWalkerTest}.
 */
public class DependencyListApiTest extends ResourceBaseTest {

    private static final String PATH = DependencyListApiSupport.PATH;

    private Response list(String token, String body) {
        return send(HttpMethod.POST, PATH, null, body, "authorization", token);
    }

    private void store(String blobPath, String json) {
        DependencyListApiSupport.storeJson(dial, blobPath, json);
    }

    private static List<String> namesOf(JsonNode root) {
        List<String> names = new ArrayList<>();
        root.get("dependencies").forEach(item -> names.add(item.get("type").asText() + ":" + item.get("name").asText()));
        return names;
    }

    @Test
    void testRouteAnswersWithOneEntryPerRootAndItsDependencies() throws Exception {
        store("platform/applications/root", "{\"endpoint\":\"http://root.invalid\",\"dependencies\":[\"applications/platform/dep\"]}");
        store("platform/applications/dep", "{\"endpoint\":\"http://dep.invalid\"}");

        Response response = list("admin", DependencyListApiSupport.rootsRequest("applications/platform/root"));

        verify(response, 200);
        JsonNode roots = ProxyUtil.MAPPER.readTree(response.body()).get("roots");
        assertEquals(1, roots.size());
        assertEquals("root", roots.get(0).get("name").asText());
        assertEquals("applications/platform/root", roots.get(0).get("url").asText());
        assertEquals("platform", roots.get(0).get("origin").asText());
        assertEquals(List.of("application:dep"), namesOf(roots.get(0)));
    }

    @Test
    void testToolSetOfThePlatformIsListedWithItsReferences() throws Exception {
        store("platform/applications/root", "{\"endpoint\":\"http://root.invalid\",\"dependencies\":[\"toolsets/platform/search\"]}");
        store("platform/toolsets/search", "{\"endpoint\":\"http://search.invalid/mcp\",\"dependencies\":[\"applications/platform/helper\"]}");
        store("platform/applications/helper", "{\"endpoint\":\"http://helper.invalid\"}");

        Response response = list("admin", DependencyListApiSupport.rootsRequest("applications/platform/root"));

        verify(response, 200);
        JsonNode root = ProxyUtil.MAPPER.readTree(response.body()).get("roots").get(0);
        assertEquals(List.of("toolset:search", "application:helper"), namesOf(root));
        assertEquals("toolsets/platform/search", root.get("dependencies").get(0).get("url").asText());
        assertEquals("platform", root.get("dependencies").get(0).get("origin").asText());
    }

    @Test
    void testUrlOfListedResourceIsAcceptedAsRootOfTheNextRequest() throws Exception {
        store("platform/applications/root", "{\"endpoint\":\"http://root.invalid\",\"dependencies\":[\"applications/platform/dep\"]}");
        store("platform/applications/dep", "{\"endpoint\":\"http://dep.invalid\",\"dependencies\":[\"applications/platform/leaf\"]}");
        store("platform/applications/leaf", "{\"endpoint\":\"http://leaf.invalid\"}");
        Response first = list("admin", DependencyListApiSupport.rootsRequest("applications/platform/root"));
        String url = ProxyUtil.MAPPER.readTree(first.body()).get("roots").get(0).get("dependencies").get(0).get("url").asText();

        Response second = list("admin", DependencyListApiSupport.rootsRequest(url));

        verify(second, 200);
        JsonNode root = ProxyUtil.MAPPER.readTree(second.body()).get("roots").get(0);
        assertEquals(url, root.get("url").asText());
        assertEquals(List.of("application:leaf"), namesOf(root));
    }

    @Test
    void testMissingFileIsNotListedAndDoesNotFailTheRequest() throws Exception {
        store("platform/applications/root", "{\"endpoint\":\"http://root.invalid\",\"icon_url\":\"files/public/a/gone.png\"}");

        Response response = list("admin", DependencyListApiSupport.rootsRequest("applications/platform/root"));

        verify(response, 200);
        assertEquals(List.of(), namesOf(ProxyUtil.MAPPER.readTree(response.body()).get("roots").get(0)));
    }

    @Test
    void testFindingsDoNotFailTheRequestAndTheLogHoldsNoValue() throws Exception {
        store("platform/applications/root", "{\"endpoint\":\"http://root.invalid\",\"dependencies\":[\"applications/platform/gone\","
                + "\"models/public/not-supported\",\"https://user:secret@example.com/x?token=abc\",\"sk-abc123\"]}");
        Response response;
        String log;
        try (Logs logs = new Logs()) {
            response = list("admin", DependencyListApiSupport.rootsRequest("applications/platform/root"));
            log = logs.text();
        }

        verify(response, 200);
        assertEquals(List.of(), namesOf(ProxyUtil.MAPPER.readTree(response.body()).get("roots").get(0)));
        assertTrue(log.contains("pointer=/dependencies/0 reason=missing"), log);
        assertTrue(log.contains("pointer=/dependencies/1 reason=unsupported"), log);
        assertTrue(log.contains("pointer=/dependencies/3 reason=missing"), log);
        assertFalse(log.contains("secret@") || log.contains("token=abc") || log.contains("sk-abc123"), "the log holds no value of a reference");
    }

    @Test
    void testRootInUserBucketIsRefusedForExplicitAdmin() throws Exception {
        String userRoot = userApplicationUrl("user", "own");

        Response response = list("admin", DependencyListApiSupport.rootsRequest(userRoot));

        assertEquals(400, response.status());
        assertFalse(response.body().contains(userRoot), "the answer does not repeat the URL");
    }

    @Test
    void testWalkDoesNotWaitForAnAdminWriteThatHoldsTheBucketLocks() throws Exception {
        store("platform/applications/root", "{\"endpoint\":\"http://root.invalid\",\"dependencies\":[\"applications/platform/dep\"]}");
        store("platform/applications/dep", "{\"endpoint\":\"http://dep.invalid\"}");
        String body = DependencyListApiSupport.rootsRequest("applications/platform/root");

        // the locks that every admin write holds are held here for the whole request, so a walk that took one would time out
        Response response = dial.getProxy().getLockService().underBucketLocks(MergedConfigStore.ADMIN_BUCKET_LOCATIONS,
                () -> assertTimeoutPreemptively(Duration.ofSeconds(20), () -> list("admin", body)));

        verify(response, 200);
        assertEquals(List.of("application:dep"), namesOf(ProxyUtil.MAPPER.readTree(response.body()).get("roots").get(0)));
    }

    @Test
    void testRootThatDoesNotExistIsNotFound() {
        verify(list("admin", DependencyListApiSupport.rootsRequest("applications/platform/gone")), 404);
    }

    @Test
    void testInvalidBodyIsBadRequestThatEchoesNothing() {
        Response response = list("admin", "not json SUBMITTED-VALUE-MARKER");

        assertEquals(400, response.status());
        assertFalse(response.body().contains("SUBMITTED-VALUE-MARKER"));
    }
}
