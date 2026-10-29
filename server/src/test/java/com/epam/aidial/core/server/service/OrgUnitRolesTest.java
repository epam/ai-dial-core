package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.OrgUnitPermission;
import com.epam.aidial.core.config.OrgUnitRole;
import com.epam.aidial.core.server.util.ProxyUtil;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.epam.aidial.core.server.service.OrgUnitRoles.ADMIN;
import static com.epam.aidial.core.server.service.OrgUnitRoles.EDITOR;
import static com.epam.aidial.core.server.service.OrgUnitRoles.MEMBER;
import static com.epam.aidial.core.server.service.OrgUnitRoles.ORG_UNITS;
import static com.epam.aidial.core.server.service.OrgUnitRoles.PUBLICATIONS;
import static com.epam.aidial.core.server.service.OrgUnitRoles.READ;
import static com.epam.aidial.core.server.service.OrgUnitRoles.ROLE_ASSIGNMENTS;
import static com.epam.aidial.core.server.service.OrgUnitRoles.VIEWER;
import static com.epam.aidial.core.server.service.OrgUnitRoles.WRITE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrgUnitRolesTest {

    @Test
    void testBuiltInRoles() {
        OrgUnitRoles roles = OrgUnitRoles.of(new Config());

        assertEquals(List.of(ADMIN, EDITOR, VIEWER, MEMBER), List.copyOf(roles.roles().keySet()));
        for (String namespace : OrgUnitRoles.CONTENT_NAMESPACES) {
            for (String role : List.of(ADMIN, EDITOR)) {
                assertTrue(roles.allows(List.of(role), READ, namespace), role + " " + namespace);
                assertTrue(roles.allows(List.of(role), WRITE, namespace), role + " " + namespace);
            }
        }
        for (String namespace : List.of(PUBLICATIONS, ROLE_ASSIGNMENTS)) {
            assertTrue(roles.allows(List.of(ADMIN), WRITE, namespace), namespace);
            assertFalse(roles.allows(List.of(EDITOR), READ, namespace), namespace);
            assertFalse(roles.allows(List.of(EDITOR), WRITE, namespace), namespace);
        }
        for (String namespace : OrgUnitRoles.CONTENT_NAMESPACES) {
            assertTrue(roles.allows(List.of(VIEWER), READ, namespace), namespace);
            assertFalse(roles.allows(List.of(VIEWER), WRITE, namespace), namespace);
            assertFalse(roles.allows(List.of(MEMBER), READ, namespace), namespace);
        }
        for (String role : List.of(ADMIN, EDITOR, VIEWER, MEMBER)) {
            assertTrue(roles.allows(List.of(role), READ, ORG_UNITS), role);
            assertFalse(roles.allows(List.of(role), WRITE, ORG_UNITS), role);
        }
        assertTrue(roles.covers(List.of(ADMIN), MEMBER));
        assertFalse(roles.covers(List.of(MEMBER), VIEWER));
    }

    @Test
    void testOrgUnitsTakeOnlyRead() {
        OrgUnitPermission write = new OrgUnitPermission(List.of(WRITE), "org-units/**");
        assertThrows(IllegalArgumentException.class, () -> OrgUnitRoles.validate(Map.of("x", new OrgUnitRole("x", List.of(write)))));
        OrgUnitPermission read = new OrgUnitPermission(List.of(READ), "org-units/**");
        assertDoesNotThrow(() -> OrgUnitRoles.validate(Map.of("x", new OrgUnitRole("x", List.of(read)))));
    }

    @Test
    void testUnknownRolesGrantNothing() {
        OrgUnitRoles roles = OrgUnitRoles.of(new Config());

        assertFalse(roles.allows(List.of("approver"), WRITE, PUBLICATIONS));
        assertFalse(roles.allows(List.of(), READ, "files"));
        assertFalse(roles.exists("approver"));
        assertFalse(roles.allows(List.of(ADMIN), READ, "rules"));
    }

    @Test
    void testConfigReplacesAndAddsRoles() {
        Config config = new Config();
        config.setOrgUnitRoles(Map.of(
                EDITOR, role(new OrgUnitPermission(List.of(WRITE), "files/**")),
                "auditor", role(new OrgUnitPermission(List.of(READ), "files/**"), new OrgUnitPermission(List.of(READ), "prompts/**"))));

        OrgUnitRoles roles = OrgUnitRoles.of(config);

        assertEquals(List.of(ADMIN, EDITOR, VIEWER, MEMBER, "auditor"), List.copyOf(roles.roles().keySet()));
        assertTrue(roles.allows(List.of(EDITOR), WRITE, "files"));
        assertFalse(roles.allows(List.of(EDITOR), READ, "files"));
        assertFalse(roles.allows(List.of(EDITOR), WRITE, "prompts"));
        assertTrue(roles.allows(List.of("auditor"), READ, "prompts"));
        assertFalse(roles.allows(List.of("auditor"), WRITE, "prompts"));
        assertTrue(roles.allows(List.of("auditor", EDITOR), WRITE, "files"));
        assertTrue(roles.allows(List.of(ADMIN), WRITE, PUBLICATIONS));
    }

    @Test
    void testPermissionsResolveRolesTogether() {
        Config config = new Config();
        config.setOrgUnitRoles(Map.of("auditor", role(new OrgUnitPermission(List.of(READ), "files/**"),
                new OrgUnitPermission(List.of(WRITE, READ), "prompts/**"))));
        OrgUnitRoles roles = OrgUnitRoles.of(config);

        assertEquals(Map.of("files", Set.of(READ), "prompts", Set.of(READ, WRITE)), roles.resolvePermissions(List.of("auditor")));
        assertEquals(List.of("files", "prompts"), List.copyOf(roles.resolvePermissions(List.of("auditor")).keySet()));
        assertEquals(List.of(READ, WRITE), List.copyOf(roles.resolvePermissions(List.of("auditor")).get("prompts")));
        assertEquals(Map.of(ORG_UNITS, Set.of(READ)), roles.resolvePermissions(List.of(MEMBER)));
        assertEquals(Set.of(READ, WRITE), roles.resolvePermissions(List.of(MEMBER, "auditor", VIEWER)).get("prompts"));
        assertEquals(Set.of(READ), roles.resolvePermissions(List.of(MEMBER, "auditor", VIEWER)).get("files"));
        assertEquals(Set.of(READ, WRITE), roles.resolvePermissions(List.of(ADMIN)).get(ROLE_ASSIGNMENTS));
        assertEquals(Map.of(), roles.resolvePermissions(List.of("approver")));
        assertEquals(Map.of(), roles.resolvePermissions(List.of()));
    }

    @Test
    void testAllPermissionsAreBothActionsOnEveryNamespace() {
        Map<String, Set<String>> all = OrgUnitRoles.resolveAllPermissions();

        assertTrue(all.keySet().containsAll(OrgUnitRoles.CONTENT_NAMESPACES));
        assertTrue(all.keySet().containsAll(List.of(PUBLICATIONS, ROLE_ASSIGNMENTS, ORG_UNITS)));
        assertEquals(OrgUnitRoles.CONTENT_NAMESPACES.size() + 3, all.size());
        all.values().forEach(actions -> assertEquals(List.of(READ, WRITE), List.copyOf(actions)));
        assertEquals(all.keySet().stream().sorted().toList(), List.copyOf(all.keySet()));
    }

    @Test
    void testAdminRedefinedWithoutRoleAssignmentsLosesThem() {
        Map<String, OrgUnitRole> configured = Map.of(ADMIN, role(new OrgUnitPermission(List.of(READ, WRITE), "files/**")));
        assertDoesNotThrow(() -> OrgUnitRoles.validate(configured));
        Config config = new Config();
        config.setOrgUnitRoles(configured);

        OrgUnitRoles roles = OrgUnitRoles.of(config);

        assertFalse(roles.allows(List.of(ADMIN), WRITE, OrgUnitRoles.ROLE_ASSIGNMENTS));
        assertFalse(roles.covers(List.of(ADMIN), EDITOR));
    }

    @Test
    void testCovers() {
        Config config = new Config();
        config.setOrgUnitRoles(Map.of("manager", role(new OrgUnitPermission(List.of(READ, WRITE), "role-assignments/**"),
                new OrgUnitPermission(List.of(READ, WRITE), "files/**"))));
        OrgUnitRoles roles = OrgUnitRoles.of(config);

        assertTrue(roles.covers(List.of(ADMIN), ADMIN));
        assertTrue(roles.covers(List.of(ADMIN), EDITOR));
        assertFalse(roles.covers(List.of(EDITOR), ADMIN));
        assertFalse(roles.covers(List.of("manager"), EDITOR));
        assertTrue(roles.covers(List.of("manager", EDITOR), "manager"));
        assertFalse(roles.covers(List.of("manager", EDITOR), ADMIN));
        assertTrue(roles.covers(List.of(), "approver"));
        assertFalse(roles.covers(List.of(ADMIN), null));
    }

    @Test
    void testValidation() {
        assertDoesNotThrow(() -> OrgUnitRoles.validate(null));
        assertDoesNotThrow(() -> OrgUnitRoles.validate(Map.of("auditor", role(new OrgUnitPermission(List.of(READ, WRITE), "role-assignments/**")))));
        assertDoesNotThrow(() -> OrgUnitRoles.validate(Map.of("nothing", role())));

        for (OrgUnitPermission bad : List.of(
                new OrgUnitPermission(List.of("delete"), "files/**"),
                new OrgUnitPermission(List.of(), "files/**"),
                new OrgUnitPermission(null, "files/**"),
                new OrgUnitPermission(List.of(READ), "files/*"),
                new OrgUnitPermission(List.of(READ), "files/Reports/**"),
                new OrgUnitPermission(List.of(READ), "rules/**"),
                new OrgUnitPermission(List.of(READ), "**"),
                new OrgUnitPermission(List.of(READ), null))) {
            assertThrows(IllegalArgumentException.class, () -> OrgUnitRoles.validate(Map.of("auditor", role(bad))), bad.toString());
        }
        assertThrows(IllegalArgumentException.class, () -> OrgUnitRoles.validate(Map.of("au/ditor", role())));
        assertThrows(IllegalArgumentException.class, () -> OrgUnitRoles.validate(Map.of("auditor", new OrgUnitRole("x", null))));
        assertThrows(IllegalArgumentException.class, () -> OrgUnitRoles.validate(Map.of("auditor",
                role(new OrgUnitPermission(Arrays.asList(READ, null), "files/**")))));
    }

    @Test
    void testRoleWithoutPermissionsIsRefused() {
        for (String body : List.of("{\"description\": \"x\"}",
                "{\"description\": \"x\", \"permission\": [{\"actions\": [\"read\"], \"pathPattern\": \"files/**\"}]}")) {
            OrgUnitRole role = ProxyUtil.convertToObject(body, OrgUnitRole.class);
            assertThrows(IllegalArgumentException.class, () -> OrgUnitRoles.validate(Map.of(ADMIN, role)), body);
        }
    }

    private static OrgUnitRole role(OrgUnitPermission... permissions) {
        return new OrgUnitRole("test", List.of(permissions));
    }
}
