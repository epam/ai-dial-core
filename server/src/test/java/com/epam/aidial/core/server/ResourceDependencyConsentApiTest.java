package com.epam.aidial.core.server;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The admin-consent gate on the existing consent API: grant/withdraw via
 * {@code POST/DELETE /v1/consent/{id}/admin-consent}, the error taxonomy, the typed record's
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
                {"kind": "dial.resourceLink", "link_id": "lnk_skills", "target": {"path": "current-user/skills/"}, "access": ["write"], "required": true}
              ]
            }
            """;

    @Test
    void testAdminCanGrantAndWithdrawConsent() {
        verify(send(HttpMethod.PUT, "/v1/applications/public/dependency-consent-app", null,
                DECLARING_APP_BODY, "authorization", "admin", "If-None-Match", "*"), 200);

        // POST without a body reaching grantAdminConsent (acceptConsent would demand one) also pins
        // the route order: USER_CONSENT's anchored pattern must not swallow the admin-consent path.
        verify(send(HttpMethod.POST, "/v1/consent/" + DECLARING_APP + "/admin-consent", null, "",
                "authorization", "admin"), 200);

        // The consent document now carries the resource half (§6.5), for a declaring app with no
        // consentRequired flag anywhere — consent is never author-controlled (§6.1).
        Response consent = send(HttpMethod.GET, "/v1/consent/" + DECLARING_APP, null, "", "authorization", "admin");
        verify(consent, 200);
        assertTrue(consent.body().contains("current-user/skills/"), () -> "Body: " + consent.body());

        verify(send(HttpMethod.DELETE, "/v1/consent/" + DECLARING_APP + "/admin-consent", null, "",
                "authorization", "admin"), 200);

        // A plain app never declares: consent is not applicable.
        verify(send(HttpMethod.PUT, "/v1/applications/public/plain-consent-app", null, """
                {
                  "endpoint": "http://application1/v1/completions",
                  "display_name": "Plain App"
                }
                """, "authorization", "admin", "If-None-Match", "*"), 200);
        verify(send(HttpMethod.POST, "/v1/consent/applications/public/plain-consent-app/admin-consent", null, "",
                "authorization", "admin"), 400);
    }

    @Test
    void testOnlyAdministratorsMayConsent() {
        verify(send(HttpMethod.PUT, "/v1/applications/public/dependency-consent-app", null,
                DECLARING_APP_BODY, "authorization", "admin", "If-None-Match", "*"), 200);

        verify(send(HttpMethod.POST, "/v1/consent/" + DECLARING_APP + "/admin-consent", null, ""), 403);
        verify(send(HttpMethod.DELETE, "/v1/consent/" + DECLARING_APP + "/admin-consent", null, ""), 403);
    }

    @Test
    void testUnknownDeploymentIsNotFound() {
        verify(send(HttpMethod.POST, "/v1/consent/unknown-app/admin-consent", null, "",
                "authorization", "admin"), 404);
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
        verify(send(HttpMethod.POST, "/v1/consent/" + DECLARING_APP + "/admin-consent", null, "",
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
            verify(send(HttpMethod.POST, "/v1/consent/" + DECLARING_APP + "/admin-consent", null, "",
                    "authorization", "admin"), 200);
            verify(send(HttpMethod.POST, "/v1/consent/" + DECLARING_APP + "/admin-consent", null, ""), 403);
            verify(send(HttpMethod.DELETE, "/v1/consent/" + DECLARING_APP + "/admin-consent", null, "",
                    "authorization", "admin"), 200);

            List<String> events = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.startsWith("event=resource_dependency_consent"))
                    .toList();

            assertEquals(3, events.size(), () -> "Events: " + events);
            assertTrue(events.get(0).contains("action=GRANT") && events.get(0).contains("outcome=SUCCESS")
                    && events.get(0).contains("targets=current-user/skills/"), () -> "Events: " + events);
            assertTrue(events.get(1).contains("action=GRANT") && events.get(1).contains("outcome=DENIED"),
                    () -> "Events: " + events);
            assertTrue(events.get(2).contains("action=WITHDRAW") && events.get(2).contains("outcome=SUCCESS")
                    && events.get(2).contains("targets=current-user/skills/"), () -> "Events: " + events);
            assertFalse(events.stream().anyMatch(message -> message.contains("lnk_")));
        } finally {
            auditLogger.detachAppender(appender);
        }
    }
}
