package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.Rule;
import com.epam.aidial.core.server.data.orgunit.RoleAssignment;
import com.epam.aidial.core.server.data.orgunit.Subject;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrgUnitSubjectsTest {

    @Test
    void testOnlyPlainClaimSourcesMatch() {
        ObjectNode claims = ProxyUtil.MAPPER.createObjectNode().put("email", "bob@acme.com");
        ProxyContext bob = mock(ProxyContext.class);
        when(bob.getExtractedClaims()).thenReturn(new ExtractedClaims("bob", List.of(), "bob", claims, null, "Bob", "bob@acme.com"));
        when(bob.getApiKeyData()).thenReturn(new ApiKeyData());
        lenient().when(bob.isOriginalRequest()).thenAnswer(invocation -> bob.getApiKeyData().getPerRequestKey() == null);
        when(bob.getUserRoles()).thenReturn(List.of());

        List<RoleAssignment> assignments = List.of(
                new RoleAssignment("1", "editor", new Subject.ClaimsPredicate("email", Rule.Function.EQUAL, List.of("bob@acme.com"))),
                new RoleAssignment("2", "admin", new Subject.ClaimsPredicate("$.email", Rule.Function.EQUAL, List.of("bob@acme.com"))));

        assertEquals(Set.of("editor"), OrgUnitSubjects.heldRoles(bob, assignments));
    }

    @Test
    void testApiKeysHoldTheRolesTheirRolesMatchAndApplicationsOnlyReadWithThem() {
        List<RoleAssignment> assignments = List.of(
                new RoleAssignment("1", "editor", new Subject.ClaimsPredicate("roles", Rule.Function.EQUAL, List.of("default"))));

        ProxyContext apiKey = mock(ProxyContext.class);
        when(apiKey.getApiKeyData()).thenReturn(new ApiKeyData());
        lenient().when(apiKey.isOriginalRequest()).thenAnswer(invocation -> apiKey.getApiKeyData().getPerRequestKey() == null);
        when(apiKey.getUserRoles()).thenReturn(List.of("default"));
        assertEquals(Set.of("editor"), OrgUnitSubjects.heldRoles(apiKey, assignments));

        ApiKeyData perRequest = new ApiKeyData();
        perRequest.setPerRequestKey("per-request");
        ProxyContext application = mock(ProxyContext.class);
        when(application.getApiKeyData()).thenReturn(perRequest);
        lenient().when(application.isOriginalRequest()).thenAnswer(invocation -> application.getApiKeyData().getPerRequestKey() == null);
        when(application.getExtractedClaims()).thenReturn(new ExtractedClaims("alice", List.of("default"), "alice", null, null, "Alice", null));
        when(application.getUserRoles()).thenReturn(List.of("default"));
        assertEquals(Set.of(), OrgUnitSubjects.heldRoles(application, assignments));
        assertEquals(Set.of("editor"), OrgUnitSubjects.rolesToReadWith(application, assignments));
    }
}
