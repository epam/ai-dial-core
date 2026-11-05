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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The admin-consent gate: grant/withdraw via
 * {@code POST/DELETE /v1/admin-consent/{id}}, the error taxonomy, the typed record's
 * invisibility to the generic Resource API, and the audit stream. Content binding (declaration
 * change ⇒ grant invalid) is proven at unit level in {@code ConsentServiceTest} and end to end
 * in the resolution PR's tests.
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

        verify(send(HttpMethod.POST, "/v1/admin-consent/" + DECLARING_APP, null, "",
                "authorization", "admin"), 200);

        // The consent document now carries the resource half (§6.5), for a declaring app with no
        // consentRequired flag anywhere — consent is never author-controlled (§6.1).
        Response consent = send(HttpMethod.GET, "/v1/consent/" + DECLARING_APP, null, "", "authorization", "admin");
        verify(consent, 200);
        assertTrue(consent.body().contains("skills/{current-user}/"), () -> "Body: " + consent.body());

        verify(send(HttpMethod.DELETE, "/v1/admin-consent/" + DECLARING_APP, null, "",
                "authorization", "admin"), 200);

        // A plain app never declares: consent is not applicable.
        verify(send(HttpMethod.PUT, "/v1/applications/public/plain-consent-app", null, """
                {
                  "endpoint": "http://application1/v1/completions",
                  "display_name": "Plain App"
                }
                """, "authorization", "admin", "If-None-Match", "*"), 200);
        verify(send(HttpMethod.POST, "/v1/admin-consent/applications/public/plain-consent-app", null, "",
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
    void testOnlyAdministratorsMayConsent() {
        verify(send(HttpMethod.PUT, "/v1/applications/public/dependency-consent-app", null,
                DECLARING_APP_BODY, "authorization", "admin", "If-None-Match", "*"), 200);

        verify(send(HttpMethod.POST, "/v1/admin-consent/" + DECLARING_APP, null, ""), 403);
        verify(send(HttpMethod.DELETE, "/v1/admin-consent/" + DECLARING_APP, null, ""), 403);
    }

    @Test
    void testUnknownDeploymentIsNotFound() {
        verify(send(HttpMethod.POST, "/v1/admin-consent/unknown-app", null, "",
                "authorization", "admin"), 404);
        verify(send(HttpMethod.GET, "/v1/admin-consent/unknown-app", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    void testAdminConsentStatusReadsTheTriState() {
        // Never granted: consented false, nothing else — no stale, no provenance.
        verify(send(HttpMethod.PUT, "/v1/applications/public/dependency-consent-app", null,
                DECLARING_APP_BODY, "authorization", "admin", "If-None-Match", "*"), 200);
        Response neverGranted = send(HttpMethod.GET, "/v1/admin-consent/" + DECLARING_APP, null, "",
                "authorization", "admin");
        verify(neverGranted, 200);
        assertEquals("{\"consented\":false}", neverGranted.body(), () -> "Body: " + neverGranted.body());

        // Granted: consented true, stale false, provenance and the approved snapshot present.
        verify(send(HttpMethod.POST, "/v1/admin-consent/" + DECLARING_APP, null, "",
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

        // Declaration changed since the grant: not consented, stale — and the last approval stays
        // visible for the panel's re-approve view.
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

        // Withdrawn: back to the clean never-granted shape.
        verify(send(HttpMethod.DELETE, "/v1/admin-consent/" + DECLARING_APP, null, "",
                "authorization", "admin"), 200);
        Response withdrawn = send(HttpMethod.GET, "/v1/admin-consent/" + DECLARING_APP, null, "",
                "authorization", "admin");
        verify(withdrawn, 200);
        assertEquals("{\"consented\":false}", withdrawn.body(), () -> "Body: " + withdrawn.body());
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

        // The status read is gated exactly like the writers — fail-closed for non-admins.
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
    void testApiKeyAdminGrantIsStampedWithTheKeysProject() {
        verify(send(HttpMethod.PUT, "/v1/applications/public/dependency-consent-app", null,
                DECLARING_APP_BODY, "authorization", "admin", "If-None-Match", "*"), 200);

        verify(send(HttpMethod.POST, "/v1/admin-consent/" + DECLARING_APP, null, "", "api-key", "adminKey1"), 200);

        Response status = send(HttpMethod.GET, "/v1/admin-consent/" + DECLARING_APP, null, "", "api-key", "adminKey1");
        verify(status, 200);
        assertTrue(status.body().contains("\"grantedBy\":\"AdminTest\""), status.body());
    }

    /**
     * The typed record is unreachable through the generic Resource API: ADMIN_CONSENT is
     * deliberately unmapped in ResourceTypes.of(), and no resource route serves the type —
     * only the consent endpoint can address it.
     */
    @Test
    void testAdminConsentRecordIsUnreachableViaTheResourceApi() {
        verify(send(HttpMethod.PUT, "/v1/applications/public/dependency-consent-app", null,
                DECLARING_APP_BODY, "authorization", "admin", "If-None-Match", "*"), 200);
        verify(send(HttpMethod.POST, "/v1/admin-consent/" + DECLARING_APP, null, "",
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
            verify(send(HttpMethod.POST, "/v1/admin-consent/" + DECLARING_APP, null, "",
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
}
