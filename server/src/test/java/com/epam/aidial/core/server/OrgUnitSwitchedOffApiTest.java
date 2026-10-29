package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * With org units off, the default, their routes answer as routes that do not exist.
 */
public class OrgUnitSwitchedOffApiTest extends ResourceBaseTest {

    @Test
    void testRoutesAreNotFound() {
        Response read = send(HttpMethod.GET, "/v1/org-units/public", null, null, "authorization", "admin");
        assertEquals(404, read.status());
        assertEquals("Route is not found", read.body());
        Response create = send(HttpMethod.POST, "/v1/org-units/public", null, "{\"name\": \"Risk\"}", "authorization", "admin");
        assertEquals(404, create.status());
        assertEquals("Route is not found", create.body());
        Response rename = send(HttpMethod.PATCH, "/v1/org-units/public/Risk", null, "{\"displayName\": \"Risk\"}", "authorization", "admin");
        assertEquals(404, rename.status());
        assertEquals("Route is not found", rename.body());
        Response remove = send(HttpMethod.DELETE, "/v1/org-units/public/Risk", null, null, "authorization", "admin");
        assertEquals(404, remove.status());
        assertEquals("Route is not found", remove.body());
        Response callerUnits = send(HttpMethod.GET, "/v1/user/org-units", null, null, "authorization", "admin");
        assertEquals(404, callerUnits.status());
        assertEquals("Route is not found", callerUnits.body());
    }
}
