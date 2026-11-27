package com.epam.aidial.core.server;

import com.epam.aidial.core.server.DependencyListApiSupport.Logs;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.data.Rule;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@code POST /v1/ops/dependency/list} for a caller who is not an explicit admin, through the real server: what the caller reaches in
 * their own bucket, in a shared bucket and in {@code public/}, the deployments their roles admit, the order of the statuses of a root,
 * and what the answer and the log never hold. The policy itself is tested in {@code DependencyAccessPolicyTest}.
 */
public class DependencyListAccessApiTest extends ResourceBaseTest {

    private static final String PATH = DependencyListApiSupport.PATH;

    private Response list(String token, String... roots) {
        return send(HttpMethod.POST, PATH, null, DependencyListApiSupport.rootsRequest(roots), "authorization", token);
    }

    private String bucketOf(String role) throws Exception {
        Response response = send(HttpMethod.GET, "/v1/bucket", null, "", "authorization", role);
        verify(response, 200);
        return ProxyUtil.MAPPER.readTree(response.body()).get("bucket").asText();
    }

    private void store(String blobPath, String json) {
        DependencyListApiSupport.storeJson(dial, blobPath, json);
    }

    private void uploadFile(String role, String path) throws Exception {
        verify(upload(HttpMethod.PUT, "/v1/files/" + bucketOf(role) + "/" + path, null, "content", "authorization", role), 200);
    }

    /** Everyone who is signed in reads {@code folder}, as an approved publication would allow. */
    private void openPublicFolder(String folder) {
        Rule everyone = new Rule();
        everyone.setSource("roles");
        everyone.setFunction(Rule.Function.TRUE);
        dial.getProxy().getRuleService().storeRules(folder, List.of(everyone));
    }

    private void rebuildConfig() {
        ((MergedConfigStore) dial.getProxy().getConfigStore()).rebuildNow();
    }

    private static JsonNode rootOf(Response response) throws Exception {
        verify(response, 200);
        return ProxyUtil.MAPPER.readTree(response.body()).get("roots").get(0);
    }

    private static List<String> describe(JsonNode root) {
        List<String> items = new ArrayList<>();
        root.get("dependencies").forEach(item -> items.add(item.get("type").asText() + ":" + item.get("name").asText() + ":"
                + item.get("origin").asText() + ":" + (item.get("url").isNull() ? "-" : "url")));
        return items;
    }

    @Test
    void testOwnApplicationAndItsFileAreListedAsPrivateWithoutTheDecryptedBucket() throws Exception {
        String bucket = bucketOf("user");
        uploadFile("user", "icon.png");
        store("Users/user/applications/own", "{\"endpoint\":\"http://own.invalid\",\"dependencies\":[\"files/" + bucket + "/icon.png\"]}");

        Response response = list("user", "applications/" + bucket + "/own");

        JsonNode root = rootOf(response);
        assertEquals("private", root.get("origin").asText());
        assertEquals("applications/" + bucket + "/own", root.get("url").asText());
        assertEquals(List.of("file:icon.png:private:url"), describe(root));
        assertEquals("files/" + bucket + "/icon.png", root.get("dependencies").get(0).get("url").asText());
        assertFalse(response.body().contains("Users/"), "the answer holds no decrypted bucket");
    }

    @Test
    void testSharedApplicationIsPrivateAndItsPublicDependencyIsPublic() throws Exception {
        openPublicFolder("public/");
        store("public/applications/open", "{\"endpoint\":\"http://open.invalid\"}");
        String shared = userApplicationUrl("admin", "shared");
        store("Users/admin/applications/shared", "{\"endpoint\":\"http://shared.invalid\",\"dependencies\":[\"applications/public/open\"]}");
        shareWithUser(shared, "[\"READ\"]");

        JsonNode root = rootOf(list("user", shared));

        assertEquals("private", root.get("origin").asText());
        assertEquals(List.of("application:open:public:url"), describe(root));
    }

    @Test
    void testDependencyTheCallerMayNotReadIsLeftOutWithoutTrace() throws Exception {
        String own = bucketOf("user");
        String other = bucketOf("admin");
        uploadFile("user", "mine.png");
        uploadFile("admin", "theirs.png");
        store("Users/user/applications/app", "{\"endpoint\":\"http://app.invalid\",\"dependencies\":[\"files/" + other + "/theirs.png\",\"files/"
                + own + "/mine.png\"]}");
        Response response;
        String log;
        try (Logs logs = new Logs()) {
            response = list("user", "applications/" + own + "/app");
            log = logs.text();
        }

        assertEquals(List.of("file:mine.png:private:url"), describe(rootOf(response)));
        assertFalse(log.contains("pointer=/dependencies/0"), "a hidden dependency leaves no record: " + log);
        assertFalse(response.body().contains("theirs.png"));
    }

    @Test
    void testPublicFolderThatDoesNotAllowTheCallerIsLeftOut() throws Exception {
        openPublicFolder("public/open/");
        Rule adminsOnly = new Rule();
        adminsOnly.setSource("roles");
        adminsOnly.setFunction(Rule.Function.EQUAL);
        adminsOnly.setTargets(List.of("admin"));
        dial.getProxy().getRuleService().storeRules("public/closed/", List.of(adminsOnly));
        verify(upload(HttpMethod.PUT, "/v1/files/public/open/a.txt", null, "a", "authorization", "admin"), 200);
        verify(upload(HttpMethod.PUT, "/v1/files/public/closed/b.txt", null, "b", "authorization", "admin"), 200);
        String own = bucketOf("user");
        store("Users/user/applications/app", "{\"endpoint\":\"http://app.invalid\",\"dependencies\":[\"files/public/closed/b.txt\","
                + "\"files/public/open/a.txt\"]}");

        assertEquals(List.of("file:a.txt:public:url"), describe(rootOf(list("user", "applications/" + own + "/app"))));
    }

    @Test
    void testDeploymentOfTheConfigurationIsListedOnlyWhenRoleAdmitsTheCaller() throws Exception {
        String own = bucketOf("user");
        // secured-app admits the role "user", gpt-4 only "power-user", and chat-gpt-35-turbo has no roles
        store("Users/user/applications/app", "{\"endpoint\":\"http://app.invalid\",\"dependencies\":[\"secured-app\",\"gpt-4\","
                + "\"chat-gpt-35-turbo\"]}");

        assertEquals(List.of("application:secured-app:file:-", "model:chat-gpt-35-turbo:file:-"),
                describe(rootOf(list("user", "applications/" + own + "/app"))));
    }

    @Test
    void testPlatformModelThatTheRolesAdmitIsFollowedToItsFile() throws Exception {
        openPublicFolder("public/open/");
        verify(upload(HttpMethod.PUT, "/v1/files/public/open/icon.png", null, "i", "authorization", "admin"), 200);
        store("platform/models/viaplatform", "{\"type\":\"chat\",\"endpoint\":\"http://model.invalid/chat\",\"icon_url\":\"files/public/open/icon.png\"}");
        rebuildConfig();
        String own = bucketOf("user");
        store("Users/user/applications/app", "{\"endpoint\":\"http://app.invalid\",\"dependencies\":[\"models/platform/viaplatform\"]}");

        assertEquals(List.of("model:viaplatform:platform:-", "file:icon.png:public:url"),
                describe(rootOf(list("user", "applications/" + own + "/app"))));
    }

    @Test
    void testInterceptorIsListed() throws Exception {
        String own = bucketOf("user");
        store("Users/user/applications/app", "{\"endpoint\":\"http://app.invalid\",\"interceptors\":[\"interceptor1\"]}");

        assertEquals(List.of("interceptor:interceptor1:file:-"), describe(rootOf(list("user", "applications/" + own + "/app"))));
    }

    @Test
    void testAnotherUsersApplicationIsForbiddenWhetherOrNotItExists() throws Exception {
        String other = bucketOf("admin");
        store("Users/admin/applications/theirs", "{\"endpoint\":\"http://theirs.invalid\"}");

        Response existing = list("user", "applications/" + other + "/theirs");
        Response missing = list("user", "applications/" + other + "/nothing");

        assertEquals(403, existing.status());
        assertEquals(403, missing.status());
        assertEquals(existing.body(), missing.body(), "the answer does not depend on whether the Application exists");
    }

    @Test
    void testMissingRootThatTheCallerMayReadIsNotFound() {
        openPublicFolder("public/");

        verify(list("user", "applications/public/gone"), 404);
    }

    @Test
    void testPlatformRootIsForbidden() {
        store("platform/applications/platform-root", "{\"endpoint\":\"http://root.invalid\"}");
        rebuildConfig();

        verify(list("user", "applications/platform/platform-root"), 403);
        verify(list("user", "applications/platform/gone"), 403);
    }

    @Test
    void testBodyOfListedApplicationNeverReachesAnswerOrLog() throws Exception {
        String own = bucketOf("user");
        store("Users/user/applications/inner", """
                {"endpoint": "http://ENDPOINT-MARKER.invalid", "user_roles": ["ROLE-MARKER"],
                 "routes": {"r": {"paths": ["/a"], "upstreams": [{"endpoint": "http://UPSTREAM-MARKER.invalid", "key": "KEY-MARKER"}]}}}
                """);
        store("Users/user/applications/outer", "{\"endpoint\":\"http://outer.invalid\",\"dependencies\":[\"applications/" + own + "/inner\"]}");
        Response response;
        String log;
        try (Logs logs = new Logs()) {
            response = list("user", "applications/" + own + "/outer");
            log = logs.text();
        }

        assertEquals(List.of("application:inner:private:url"), describe(rootOf(response)));
        for (String marker : List.of("ENDPOINT-MARKER", "ROLE-MARKER", "UPSTREAM-MARKER", "KEY-MARKER")) {
            assertFalse(response.body().contains(marker), marker);
            assertFalse(log.contains(marker), marker);
        }
    }

    /** A caller whose only standing is a global reader gets nothing more than their own reach. */
    public static class GlobalReader extends ResourceBaseTest {

        @Override
        protected JsonObject additionalSettingsOverrides() {
            return new JsonObject().put("access", new JsonObject()
                    .put("admin", new JsonObject().put("rules", new JsonArray()
                            .add(new JsonObject().put("source", "roles").put("function", "EQUAL").put("targets", new JsonArray().add("admin")))))
                    .put("globalReader", new JsonObject().put("rules", new JsonArray()
                            .add(new JsonObject().put("source", "roles").put("function", "EQUAL").put("targets", new JsonArray().add("user"))))));
        }

        @Test
        void testGlobalReaderDoesNotSeeModelTheirRolesDoNotAdmit() throws Exception {
            Response bucket = send(HttpMethod.GET, "/v1/bucket", null, "", "authorization", "user");
            String own = ProxyUtil.MAPPER.readTree(bucket.body()).get("bucket").asText();
            DependencyListApiSupport.storeJson(dial, "Users/user/applications/app",
                    "{\"endpoint\":\"http://app.invalid\",\"dependencies\":[\"gpt-4\",\"chat-gpt-35-turbo\"]}");

            Response response = send(HttpMethod.POST, PATH, null, DependencyListApiSupport.rootsRequest("applications/" + own + "/app"),
                    "authorization", "user");

            verify(response, 200);
            JsonNode dependencies = ProxyUtil.MAPPER.readTree(response.body()).get("roots").get(0).get("dependencies");
            assertEquals(1, dependencies.size(), response.body());
            assertEquals("chat-gpt-35-turbo", dependencies.get(0).get("name").asText());
        }
    }
}
