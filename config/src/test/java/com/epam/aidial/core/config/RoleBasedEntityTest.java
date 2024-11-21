package com.epam.aidial.core.config;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RoleBasedEntityTest {

    @Test
    public void testHasAssessByRole_DeploymentRolesEmpty() {
        Deployment deployment = new Model();
        deployment.setUserRoles(Collections.emptySet());

        assertFalse(deployment.hasAccess(null));
    }

    @Test
    public void testHasAssessByRole_DeploymentRolesIsNull() {
        Deployment deployment = new Model();
        deployment.setUserRoles(null);

        assertTrue(deployment.hasAccess(null));
    }

    @Test
    public void testHasAssessByRole_RoleMismatch() {
        Deployment deployment = new Model();
        deployment.setUserRoles(Set.of("role1"));

        assertFalse(deployment.hasAccess(Collections.emptyList()));
    }

    @Test
    public void testHasAssessByRole_Success() {
        Deployment deployment = new Model();
        deployment.setUserRoles(Set.of("role1", "role3"));

        assertTrue(deployment.hasAccess(List.of("role2", "role3")));
    }
}
