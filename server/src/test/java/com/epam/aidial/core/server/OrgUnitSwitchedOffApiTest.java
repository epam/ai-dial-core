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
    }
}
