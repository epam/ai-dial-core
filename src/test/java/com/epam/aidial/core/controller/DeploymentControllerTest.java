package com.epam.aidial.core.controller;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Deployment;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DeploymentControllerTest {

    @Test
    public void testHasAssessByRole_DeploymentRolesEmpty() {
        ProxyContext proxyContext = mock(ProxyContext.class);
        Deployment deployment = mock(Deployment.class);

        when(deployment.getUserRoles()).thenReturn(Collections.emptySet());

        assertFalse(DeploymentController.hasAccess(proxyContext, deployment));
    }

    @Test
    public void testHasAssessByRole_DeploymentRolesIsNull() {
        ProxyContext proxyContext = mock(ProxyContext.class);
        Deployment deployment = mock(Deployment.class);

        when(deployment.getUserRoles()).thenReturn(null);

        assertTrue(DeploymentController.hasAccess(proxyContext, deployment));
    }

    @Test
    public void testHasAssessByRole_RoleMismatch() {
        ProxyContext proxyContext = mock(ProxyContext.class);
        Deployment deployment = mock(Deployment.class);

        when(deployment.getUserRoles()).thenReturn(Set.of("role1"));
        when(proxyContext.getUserRoles()).thenReturn(Collections.emptyList());

        assertFalse(DeploymentController.hasAccess(proxyContext, deployment));
    }

    @Test
    public void testHasAssessByRole_Success() {
        ProxyContext proxyContext = mock(ProxyContext.class);
        Deployment deployment = mock(Deployment.class);

        when(deployment.getUserRoles()).thenReturn(Set.of("role1", "role3"));
        when(proxyContext.getUserRoles()).thenReturn(List.of("role2", "role3"));

        assertTrue(DeploymentController.hasAccess(proxyContext, deployment));
    }
}
