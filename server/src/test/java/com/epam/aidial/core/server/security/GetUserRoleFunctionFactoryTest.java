package com.epam.aidial.core.server.security;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GetUserRoleFunctionFactoryTest {

    @Test
    public void testExtractGroups() {
        JsonObject json = new JsonObject("""
                {}
                """);

        List<String> res = GetUserRoleFunctionFactory.extractGroups(json);

        assertNotNull(res);
        assertTrue(res.isEmpty());

        json = new JsonObject("""
                {
                 "memberships": [
                 {"membership": "abc"}
                 ]
                }
                """);

        res = GetUserRoleFunctionFactory.extractGroups(json);

        assertNotNull(res);
        assertTrue(res.isEmpty());

        json = new JsonObject("""
                {
                 "memberships": [
                 {
                    "membership": "abc",
                    "groupKey": {}
                  }
                 ]
                }
                """);

        res = GetUserRoleFunctionFactory.extractGroups(json);

        assertNotNull(res);
        assertTrue(res.isEmpty());

        json = new JsonObject("""
                {
                 "memberships": [
                 {
                    "membership": "abc",
                    "groupKey": {
                        "id": "my-group"
                    }
                  }
                 ]
                }
                """);

        res = GetUserRoleFunctionFactory.extractGroups(json);

        assertNotNull(res);
        assertEquals(1, res.size());
        assertEquals("my-group", res.getFirst());
    }
}
