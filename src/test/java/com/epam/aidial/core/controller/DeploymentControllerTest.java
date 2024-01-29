package com.epam.aidial.core.controller;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.config.Role;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DeploymentControllerTest {

    @Test
    public void testHasAssessByLimits_KeyIsNull() {
        ProxyContext proxyContext = mock(ProxyContext.class);
        Deployment deployment = mock(Deployment.class);
        assertTrue(DeploymentController.hasAssessByLimits(proxyContext, deployment));
    }

    @Test
    public void testHasAssessByLimits_RoleNotFound() {
        ProxyContext proxyContext = mock(ProxyContext.class);
        Deployment deployment = mock(Deployment.class);
        Key key = new Key();
        key.setRole("bad-role");
        when(proxyContext.getKey()).thenReturn(key);
        Config config = new Config();
        config.setRoles(Map.of("role1", new Role()));
        when(proxyContext.getConfig()).thenReturn(config);
        assertFalse(DeploymentController.hasAssessByLimits(proxyContext, deployment));
    }

    @Test
    public void testHasAssessByLimits_LimitIsNull() {
        ProxyContext proxyContext = mock(ProxyContext.class);
        Deployment deployment = mock(Deployment.class);
        Key key = new Key();
        key.setRole("role1");
        when(proxyContext.getKey()).thenReturn(key);
        Config config = new Config();
        Role role = new Role();
        role.setLimits(Map.of("model1", new Limit()));
        when(deployment.getName()).thenReturn("unknown-model");
        config.setRoles(Map.of("role1", role));
        when(proxyContext.getConfig()).thenReturn(config);
        assertFalse(DeploymentController.hasAssessByLimits(proxyContext, deployment));
    }

    @Test
    public void testHasAssessByLimits_LimitIsNegative() {
        ProxyContext proxyContext = mock(ProxyContext.class);
        Deployment deployment = mock(Deployment.class);
        Key key = new Key();
        key.setRole("role1");
        when(proxyContext.getKey()).thenReturn(key);
        Config config = new Config();
        Role role = new Role();
        Limit limit = new Limit();
        limit.setDay(-1);
        role.setLimits(Map.of("model1", limit));
        when(deployment.getName()).thenReturn("model1");
        config.setRoles(Map.of("role1", role));
        when(proxyContext.getConfig()).thenReturn(config);
        assertFalse(DeploymentController.hasAssessByLimits(proxyContext, deployment));
    }

    @Test
    public void testHasAssessByLimits_Success() {
        ProxyContext proxyContext = mock(ProxyContext.class);
        Deployment deployment = mock(Deployment.class);
        Key key = new Key();
        key.setRole("role1");
        when(proxyContext.getKey()).thenReturn(key);
        Config config = new Config();
        Role role = new Role();
        Limit limit = new Limit();
        role.setLimits(Map.of("model1", limit));
        when(deployment.getName()).thenReturn("model1");
        config.setRoles(Map.of("role1", role));
        when(proxyContext.getConfig()).thenReturn(config);
        assertTrue(DeploymentController.hasAssessByLimits(proxyContext, deployment));
    }

    @Test
    public void testHasAssessByRole_DeploymentRolesEmpty() {
        ProxyContext proxyContext = mock(ProxyContext.class);
        Deployment deployment = mock(Deployment.class);

        when(deployment.getUserRoles()).thenReturn(Collections.emptySet());

        assertTrue(DeploymentController.hasAccessByUserRoles(proxyContext, deployment));
    }

    @Test
    public void testHasAssessByRole_DeploymentRolesIsNull() {
        ProxyContext proxyContext = mock(ProxyContext.class);
        Deployment deployment = mock(Deployment.class);

        when(deployment.getUserRoles()).thenReturn(Collections.emptySet());

        assertTrue(DeploymentController.hasAccessByUserRoles(proxyContext, deployment));
    }

    @Test
    public void testHasAssessByRole_RoleMismatch() {
        ProxyContext proxyContext = mock(ProxyContext.class);
        Deployment deployment = mock(Deployment.class);

        when(deployment.getUserRoles()).thenReturn(Set.of("role1"));
        when(proxyContext.getUserRoles()).thenReturn(Collections.emptyList());

        assertFalse(DeploymentController.hasAccessByUserRoles(proxyContext, deployment));
    }

    @Test
    public void testHasAssessByRole_Success() {
        ProxyContext proxyContext = mock(ProxyContext.class);
        Deployment deployment = mock(Deployment.class);

        when(deployment.getUserRoles()).thenReturn(Set.of("role1", "role3"));
        when(proxyContext.getUserRoles()).thenReturn(List.of("role2", "role3"));

        assertTrue(DeploymentController.hasAccessByUserRoles(proxyContext, deployment));
    }
}
