package com.epam.aidial.core.server;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.consent.AdminConsentStatus;
import com.epam.aidial.core.server.data.consent.Consent;
import com.epam.aidial.core.server.util.ProxyUtil;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The admin-consent gate: grant/withdraw via
 * {@code POST/DELETE /v1/admin-consent/{id}}, the error taxonomy, the typed record's
 * invisibility to the generic Resource API, and the audit stream.
 */
public class ResourceDependencyConsentApiTest extends ResourceBaseTest {

    private static final String DECLARING_APP = "applications/public/dependency-consent-app";

    private static final String DECLARING_APP_BODY = """
            {
              "endpoint": "http://application1/v1/completions",
              "display_name": "Dependency Consent App",
              "resource_dependencies": [
                {"kind": "dial.resourceLink", "link_id": "lnk_skills", "target": {"path": "skills/{current-user}/"}, "access": ["write"], "required": true}
              ]
            }
            """;

    @Test
    void testAdminCanGrantAndWithdrawConsent() {
        verify(send(HttpMethod.PUT, "/v1/applications/public/dependency-consent-app", null,
                DECLARING_APP_BODY, "authorization", "admin", "If-None-Match", "*"), 200);

        verify(send(HttpMethod.POST, "/v1/admin-consent/" + DECLARING_APP, null, reviewedResources(DECLARING_APP),
                "authorization", "admin"), 200);

        // the consent document carries the declared resources, though no deployment sets consentRequired
        Response consent = send(HttpMethod.GET, "/v1/consent/" + DECLARING_APP, null, "", "authorization", "admin");
        verify(consent, 200);
        assertTrue(consent.body().contains("skills/{current-user}/"), () -> "Body: " + consent.body());

        verify(send(HttpMethod.DELETE, "/v1/admin-consent/" + DECLARING_APP, null, "",
                "authorization", "admin"), 200);

        verify(send(HttpMethod.PUT, "/v1/applications/public/plain-consent-app", null, """
                {
                  "endpoint": "http://application1/v1/completions",
                  "display_name": "Plain App"
                }
                """, "authorization", "admin", "If-None-Match", "*"), 200);
        verify(send(HttpMethod.POST, "/v1/admin-consent/applications/public/plain-consent-app", null, "{\"resources\":[]}",
                "authorization", "admin"), 400);
    }

    @Test
    void testAdminConsentSuffixOnTheUserConsentPathIsUserConsent() {
        verify(send(HttpMethod.PUT, "/v1/applications/public/dependency-consent-app", null,
                DECLARING_APP_BODY, "authorization", "admin", "If-None-Match", "*"), 200);

        // an id ending in /admin-consent is an ordinary user-consent id: the user-consent POST demands a body
        verify(send(HttpMethod.POST, "/v1/consent/" + DECLARING_APP + "/admin-consent", null, "",
                "authorization", "admin"), 400);

        Response status = send(HttpMethod.GET, "/v1/admin-consent/" + DECLARING_APP, null, "", "authorization", "admin");
        verify(status, 200);
        assertEquals("{\"consented\":false}", status.body(), () -> "Body: " + status.body());
    }

    @Test
    void testGrantApprovesOnlyTheReviewedDeclaration() {
        verify(send(HttpMethod.PUT, "/v1/applications/public/dependency-consent-app", null,
                DECLARING_APP_BODY, "authorization", "admin", "If-None-Match", "*"), 200);
        String reviewed = reviewedResources(DECLARING_APP);

        verify(send(HttpMethod.POST, "/v1/admin-consent/" + DECLARING_APP, null, "", "authorization", "admin"), 400);

        // another administrator widens the declaration between the review and the grant
        verify(send(HttpMethod.PUT, "/v1/applications/public/dependency-consent-app", null, """
                {
                  "endpoint": "http://application1/v1/completions",
                  "display_name": "Dependency Consent App",
                  "resource_dependencies": [
                    {"kind": "dial.resourceLink", "link_id": "lnk_skills", "target": {"path": "skills/{current-user}/"}, "access": ["write"], "required": true},
                    {"kind": "dial.resourceLink", "link_id": "lnk_files", "target": {"path": "files/{current-user}/"}, "access": ["write"]}
                  ]
                }
                """, "authorization", "admin"), 200);
        verify(send(HttpMethod.POST, "/v1/admin-consent/" + DECLARING_APP, null, reviewed, "authorization", "admin"), 409);
        Response status = send(HttpMethod.GET, "/v1/admin-consent/" + DECLARING_APP, null, "", "authorization", "admin");
        assertEquals("{\"consented\":false}", status.body(), () -> "Body: " + status.body());

        verify(send(HttpMethod.POST, "/v1/admin-consent/" + DECLARING_APP, null, reviewedResources(DECLARING_APP),
                "authorization", "admin"), 200);
    }

    @Test
    void testAdminManagesConsentOfConfigAppOutsideItsUserRoles() {
        // secured-app is limited to the "user" role, which the admin does not have
        Response status = send(HttpMethod.GET, "/v1/admin-consent/secured-app", null, "", "authorization", "admin");
        verify(status, 200);
        assertEquals("{\"consented\":false}", status.body(), () -> "Body: " + status.body());
    }

    @Test
    void testOnlyAdministratorsMayConsent() {
        verify(send(HttpMethod.PUT, "/v1/applications/public/dependency-consent-app", null,
                DECLARING_APP_BODY, "authorization", "admin", "If-None-Match", "*"), 200);

        verify(send(HttpMethod.POST, "/v1/admin-consent/" + DECLARING_APP, null, ""), 403);
        verify(send(HttpMethod.DELETE, "/v1/admin-consent/" + DECLARING_APP, null, ""), 403);
    }

    @Test
    void testUnknownDeploymentIsNotFound() {
        verify(send(HttpMethod.POST, "/v1/admin-consent/unknown-app", null, "{\"resources\":[]}",
                "authorization", "admin"), 404);
        verify(send(HttpMethod.GET, "/v1/admin-consent/unknown-app", null, "",
                "authorization", "admin"), 404);
        // a resource that is no deployment at all
        verify(send(HttpMethod.GET, "/v1/admin-consent/files/public/x", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    void testAdminConsentStatusReadsTheTriState() {
        verify(send(HttpMethod.PUT, "/v1/applications/public/dependency-consent-app", null,
                DECLARING_APP_BODY, "authorization", "admin", "If-None-Match", "*"), 200);
        Response neverGranted = send(HttpMethod.GET, "/v1/admin-consent/" + DECLARING_APP, null, "",
                "authorization", "admin");
        verify(neverGranted, 200);
        assertEquals("{\"consented\":false}", neverGranted.body(), () -> "Body: " + neverGranted.body());

        verify(send(HttpMethod.POST, "/v1/admin-consent/" + DECLARING_APP, null, reviewedResources(DECLARING_APP),
                "authorization", "admin"), 200);
        Response granted = send(HttpMethod.GET, "/v1/admin-consent/" + DECLARING_APP, null, "",
                "authorization", "admin");
        verify(granted, 200);
        AdminConsentStatus live = ProxyUtil.convertToObject(granted.body(), AdminConsentStatus.class);
        assertTrue(live.isConsented());
        assertEquals(Boolean.FALSE, live.getStale());
        assertEquals("admin", live.getGrantedBy());
        assertNotNull(live.getGrantedAt());
        assertEquals(List.of(entry("skills/{current-user}/", ResourceAccessType.WRITE)), live.getGrantedResources());

        // declaration changed since the grant
        verify(send(HttpMethod.PUT, "/v1/applications/public/dependency-consent-app", null, """
                {
                  "endpoint": "http://application1/v1/completions",
                  "display_name": "Dependency Consent App",
                  "resource_dependencies": [
                    {"kind": "dial.resourceLink", "link_id": "lnk_extra", "target": {"path": "files/{current-user}/dep-extra/"}, "access": ["read"]}
                  ]
                }
                """, "authorization", "admin"), 200);
        Response stale = send(HttpMethod.GET, "/v1/admin-consent/" + DECLARING_APP, null, "",
                "authorization", "admin");
        verify(stale, 200);
        AdminConsentStatus staleStatus = ProxyUtil.convertToObject(stale.body(), AdminConsentStatus.class);
        assertFalse(staleStatus.isConsented());
        assertEquals(Boolean.TRUE, staleStatus.getStale());
        assertEquals(live.getGrantedBy(), staleStatus.getGrantedBy(), "the last approval's provenance survives the stale transition");
        assertEquals(live.getGrantedAt(), staleStatus.getGrantedAt());
        assertEquals(live.getGrantedResources(), staleStatus.getGrantedResources(),
                "the approved snapshot, not the current declaration, is shown for the re-approve view");

        verify(send(HttpMethod.DELETE, "/v1/admin-consent/" + DECLARING_APP, null, "",
                "authorization", "admin"), 200);
        Response withdrawn = send(HttpMethod.GET, "/v1/admin-consent/" + DECLARING_APP, null, "",
                "authorization", "admin");
        verify(withdrawn, 200);
        assertEquals("{\"consented\":false}", withdrawn.body(), () -> "Body: " + withdrawn.body());
    }

    @Test
    void testDeletingTheApplicationDeletesItsAdminConsent() {
        verify(send(HttpMethod.PUT, "/v1/applications/public/dependency-consent-app", null,
                DECLARING_APP_BODY, "authorization", "admin", "If-None-Match", "*"), 200);
        verify(send(HttpMethod.POST, "/v1/admin-consent/" + DECLARING_APP, null, reviewedResources(DECLARING_APP),
                "authorization", "admin"), 200);

        verify(send(HttpMethod.DELETE, "/v1/applications/public/dependency-consent-app", null, "", "authorization", "admin"), 200);
        // the same path and declaration again: nobody has approved this application
        verify(send(HttpMethod.PUT, "/v1/applications/public/dependency-consent-app", null,
                DECLARING_APP_BODY, "authorization", "admin", "If-None-Match", "*"), 200);

        Response status = send(HttpMethod.GET, "/v1/admin-consent/" + DECLARING_APP, null, "", "authorization", "admin");
        verify(status, 200);
        assertEquals("{\"consented\":false}", status.body(), () -> "Body: " + status.body());
    }

    @Test
    void testMovedApplicationKeepsItsApprovalAndCopyStartsUnapproved() {
        verify(send(HttpMethod.PUT, "/v1/applications/public/move-src-app", null,
                DECLARING_APP_BODY, "authorization", "admin", "If-None-Match", "*"), 200);
        verify(send(HttpMethod.POST, "/v1/admin-consent/applications/public/move-src-app", null,
                reviewedResources("applications/public/move-src-app"), "authorization", "admin"), 200);

        verify(send(HttpMethod.POST, "/v1/ops/resource/move", null, """
                {"sourceUrl": "applications/public/move-src-app", "destinationUrl": "applications/public/move-dst-app"}
                """, "authorization", "admin"), 200);
        assertConsented("applications/public/move-dst-app", true);

        // nothing is left behind at the old path
        verify(send(HttpMethod.PUT, "/v1/applications/public/move-src-app", null,
                DECLARING_APP_BODY, "authorization", "admin", "If-None-Match", "*"), 200);
        assertConsented("applications/public/move-src-app", false);

        verify(send(HttpMethod.POST, "/v1/ops/resource/copy", null, """
                {"sourceUrl": "applications/public/move-dst-app", "destinationUrl": "applications/public/copy-app"}
                """, "authorization", "admin"), 200);
        assertConsented("applications/public/copy-app", false);
        // an unapproved application moved over an approved one does not inherit its approval
        verify(send(HttpMethod.POST, "/v1/ops/resource/move", null, """
                {"sourceUrl": "applications/public/copy-app", "destinationUrl": "applications/public/move-dst-app", "overwrite": true}
                """, "authorization", "admin"), 200);
        assertConsented("applications/public/move-dst-app", false);
    }

    private void assertConsented(String applicationId, boolean consented) {
        Response status = send(HttpMethod.GET, "/v1/admin-consent/" + applicationId, null, "", "authorization", "admin");
        verify(status, 200);
        assertEquals(consented, new JsonObject(status.body()).getBoolean("consented"), () -> "Body: " + status.body());
    }

    @Test
    void testDeletingPlatformApplicationDeletesItsAdminConsent() {
        // a platform application is served from the config under its short name, not its url
        String reviewed = """
                {"resources": [{"url": "skills/{current-user}/", "access": ["WRITE"]}]}
                """;
        verify(send(HttpMethod.PUT, "/v1/applications/platform/platform-consent-app", null,
                DECLARING_APP_BODY, "authorization", "admin", "If-None-Match", "*"), 200);
        verify(send(HttpMethod.POST, "/v1/admin-consent/platform-consent-app", null, reviewed, "authorization", "admin"), 200);

        verify(send(HttpMethod.DELETE, "/v1/applications/platform/platform-consent-app", null, "", "authorization", "admin"), 204);
        verify(send(HttpMethod.PUT, "/v1/applications/platform/platform-consent-app", null,
                DECLARING_APP_BODY, "authorization", "admin", "If-None-Match", "*"), 200);

        Response status = send(HttpMethod.GET, "/v1/admin-consent/platform-consent-app", null, "", "authorization", "admin");
        verify(status, 200);
        assertEquals("{\"consented\":false}", status.body(), () -> "Body: " + status.body());
    }

    @Test
    void testAdminConsentStatusForNonDeclarersAndNonAdmins() {
        // A non-declaring app is a legitimate "nothing consented" answer, not an error.
        verify(send(HttpMethod.PUT, "/v1/applications/public/plain-consent-app", null, """
                {
                  "endpoint": "http://application1/v1/completions",
                  "display_name": "Plain App"
                }
                """, "authorization", "admin", "If-None-Match", "*"), 200);
        Response status = send(HttpMethod.GET, "/v1/admin-consent/applications/public/plain-consent-app",
                null, "", "authorization", "admin");
        verify(status, 200);
        assertEquals("{\"consented\":false}", status.body(), () -> "Body: " + status.body());

        verify(send(HttpMethod.GET, "/v1/admin-consent/applications/public/plain-consent-app",
                null, ""), 403);
    }

    @Test
    void testAdminConsentIsRefusedToPerRequestKeys() {
        // an application holding an admin user's per-request key is still not an administrator
        ApiKeyData perRequestKey = createAppKey("admin", Map.of());
        apiKeyStore.assignPerRequestApiKey(perRequestKey);
        String key = perRequestKey.getPerRequestKey();

        verify(send(HttpMethod.GET, "/v1/admin-consent/" + DECLARING_APP, null, "", "api-key", key), 403);
        verify(send(HttpMethod.POST, "/v1/admin-consent/" + DECLARING_APP, null, "", "api-key", key), 403);
        verify(send(HttpMethod.DELETE, "/v1/admin-consent/" + DECLARING_APP, null, "", "api-key", key), 403);
    }

    @Test
    @DialConfigLocation("dial-config/admin-api-key-config.json")
    void testApiKeyAdminGrantIsStampedAndAuditedWithTheKeysProject() {
        verify(send(HttpMethod.PUT, "/v1/applications/public/dependency-consent-app", null,
                DECLARING_APP_BODY, "authorization", "admin", "If-None-Match", "*"), 200);

        Logger auditLogger = (Logger) LoggerFactory.getLogger("DIAL_RESOURCE_DEPS_AUDIT");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
        try {
            verify(send(HttpMethod.POST, "/v1/admin-consent/" + DECLARING_APP, null, reviewedResources(DECLARING_APP), "api-key", "adminKey1"), 200);
        } finally {
            auditLogger.detachAppender(appender);
        }
        List<String> grants = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("event=resource_dependency_consent action=GRANT"))
                .toList();
        assertEquals(1, grants.size(), () -> "Events: " + grants);
        assertTrue(grants.getFirst().contains(" admin_user_id=AdminTest "), () -> "Events: " + grants);

        Response status = send(HttpMethod.GET, "/v1/admin-consent/" + DECLARING_APP, null, "", "api-key", "adminKey1");
        verify(status, 200);
        assertTrue(status.body().contains("\"grantedBy\":\"AdminTest\""), status.body());
    }

    /** ADMIN_CONSENT is unmapped in ResourceTypes.of(): no Resource API route serves the record. */
    @Test
    void testAdminConsentRecordIsUnreachableViaTheResourceApi() {
        verify(send(HttpMethod.PUT, "/v1/applications/public/dependency-consent-app", null,
                DECLARING_APP_BODY, "authorization", "admin", "If-None-Match", "*"), 200);
        verify(send(HttpMethod.POST, "/v1/admin-consent/" + DECLARING_APP, null, reviewedResources(DECLARING_APP),
                "authorization", "admin"), 200);

        Response response = send(HttpMethod.GET, "/v1/admin_consent/public/" + DECLARING_APP, null, "",
                "authorization", "admin");
        assertEquals(404, response.status(), () -> "No route may serve the internal type. Body: " + response.body());
    }

    @Test
    void testConsentDecisionsAreAudited() {
        Logger auditLogger = (Logger) LoggerFactory.getLogger("DIAL_RESOURCE_DEPS_AUDIT");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
        try {
            verify(send(HttpMethod.PUT, "/v1/applications/public/dependency-consent-app", null,
                    DECLARING_APP_BODY, "authorization", "admin", "If-None-Match", "*"), 200);
            verify(send(HttpMethod.POST, "/v1/admin-consent/" + DECLARING_APP, null, reviewedResources(DECLARING_APP),
                    "authorization", "admin"), 200);
            verify(send(HttpMethod.POST, "/v1/admin-consent/" + DECLARING_APP, null, ""), 403);
            verify(send(HttpMethod.DELETE, "/v1/admin-consent/" + DECLARING_APP, null, "",
                    "authorization", "admin"), 200);

            List<String> events = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.startsWith("event=resource_dependency_consent"))
                    .toList();

            assertEquals(3, events.size(), () -> "Events: " + events);
            assertTrue(events.get(0).contains("action=GRANT") && events.get(0).contains("outcome=SUCCESS")
                    && events.get(0).contains("targets=skills/{current-user}/"), () -> "Events: " + events);
            assertTrue(events.get(1).contains("action=GRANT") && events.get(1).contains("outcome=DENIED"),
                    () -> "Events: " + events);
            assertTrue(events.get(2).contains("action=WITHDRAW") && events.get(2).contains("outcome=SUCCESS")
                    && events.get(2).contains("targets=skills/{current-user}/"), () -> "Events: " + events);
            assertFalse(events.stream().anyMatch(message -> message.contains("lnk_")));
        } finally {
            auditLogger.detachAppender(appender);
        }
    }

    private static Consent.ResourceEntry entry(String url, ResourceAccessType... access) {
        Consent.ResourceEntry entry = new Consent.ResourceEntry();
        entry.setUrl(url);
        entry.setAccess(Set.of(access));
        return entry;
    }

    /** Admin rules unconfigured: admin consent is refused to everyone, a matching role included. */
    public static class AdminRulesUnconfigured extends ResourceBaseTest {

        @Override
        protected JsonObject additionalSettingsOverrides() {
            return new JsonObject().put("access", new JsonObject().put("admin", new JsonObject().put("rules", new JsonArray())));
        }

        @Test
        void testAdminConsentIsRefusedWithoutAdminRules() {
            // refused before the application is looked up: 403, not 404
            for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.DELETE)) {
                verify(send(method, "/v1/admin-consent/unknown-app", null, "", "authorization", "admin"), 403);
            }
        }
    }

    /** A user's application is approved when it is published; in the user's bucket it is out of an administrator's reach. */
    public static class UserAuthoredApplications extends ResourceBaseTest {

        @Override
        protected JsonObject additionalSettingsOverrides() {
            return new JsonObject().put("applications", new JsonObject().put("allowUserResourceDependencies", true));
        }

        @Test
        void testPublishedUserApplicationIsApprovedAndResolves() {
            String userUrl = "applications/" + bucket + "/user-dep-app";
            verify(send(HttpMethod.PUT, "/v1/" + userUrl, null, """
                    {
                      "endpoint": "http://localhost:4848/chat/completions",
                      "display_name": "User Dependency App",
                      "resource_dependencies": [
                        {"kind": "dial.resourceLink", "link_id": "lnk_prompts",
                         "target": {"path": "prompts/{current-user}/by-app/"}, "access": ["write"], "required": true}
                      ]
                    }
                    """), 200);
            verify(send(HttpMethod.GET, "/v1/admin-consent/" + userUrl, null, "", "authorization", "admin"), 403);
            verify(send(HttpMethod.POST, "/v1/admin-consent/" + userUrl, null, """
                    {"resources": [{"url": "prompts/{current-user}/by-app/", "access": ["WRITE"]}]}
                    """, "authorization", "admin"), 403);

            Response publication = send(HttpMethod.POST, "/v1/ops/publication/create", null, """
                    {"name": "User app", "targetFolder": "public/folder/",
                     "resources": [{"action": "ADD", "sourceUrl": "%s", "targetUrl": "applications/public/folder/user-dep-app"}],
                     "rules": [{"source": "roles", "function": "TRUE"}]}
                    """.formatted(userUrl));
            verify(publication, 200);
            JsonObject created = new JsonObject(publication.body());
            Response reviewCopy = send(HttpMethod.GET, "/v1/" + created.getJsonArray("resources").getJsonObject(0).getString("reviewUrl"),
                    null, "", "authorization", "admin");
            verify(reviewCopy, 200);
            assertTrue(reviewCopy.body().contains("prompts/{current-user}/by-app/"), () -> "Body: " + reviewCopy.body());
            // approved on the review copy; the approval moves with the application when the publication is approved
            String reviewUrl = created.getJsonArray("resources").getJsonObject(0).getString("reviewUrl");
            verify(send(HttpMethod.POST, "/v1/admin-consent/" + reviewUrl, null, reviewedResources(reviewUrl),
                    "authorization", "admin"), 200);
            verify(send(HttpMethod.POST, "/v1/ops/publication/approve", null,
                    new JsonObject().put("url", created.getString("url")).encode(), "authorization", "admin"), 200);

            String publishedUrl = "applications/public/folder/user-dep-app";

            String callerBucket = new JsonObject(send(HttpMethod.GET, "/v1/bucket", null, "", "authorization", "user").body()).getString("bucket");
            AtomicReference<Integer> writeStatus = new AtomicReference<>();
            try (TestWebServer server = new TestWebServer(4848)) {
                server.map(HttpMethod.POST, "/chat/completions", request -> {
                    writeStatus.set(send(HttpMethod.PUT, "/v1/prompts/%s/by-app/p".formatted(callerBucket), null,
                            "{\"id\":\"p\",\"folderId\":\"by-app/\",\"name\":\"p\",\"content\":\"c\"}",
                            "api-key", request.getHeader("Api-Key")).status());
                    return TestWebServer.createResponse(200, "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"choices\":[]}",
                            "Content-Type", "application/json");
                });
                Response completion = send(HttpMethod.POST, "/openai/deployments/%s/chat/completions".formatted(publishedUrl), null,
                        "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", "authorization", "user", "Content-Type", "application/json");
                assertEquals(200, completion.status(), () -> "Body: " + completion.body());
            }
            assertEquals(200, writeStatus.get(), "the published application's key must carry the approved grant");
        }
    }
}
