package com.epam.aidial.core.server.orgunit;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.Rule;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrgUnitSubjectsTest {

    @Test
    void testOnlyPlainClaimSourcesMatch() {
        ObjectNode claims = ProxyUtil.MAPPER.createObjectNode().put("email", "bob@acme.com");
        ProxyContext bob = mock(ProxyContext.class);
        when(bob.getExtractedClaims()).thenReturn(new ExtractedClaims("bob", List.of(), "bob", claims, null, "Bob"));
        when(bob.getApiKeyData()).thenReturn(new ApiKeyData());
        when(bob.getUserRoles()).thenReturn(List.of());

        List<RoleAssignment> assignments = List.of(
                new RoleAssignment("1", "editor", new Subject.ClaimsPredicate("email", Rule.Function.EQUAL, List.of("bob@acme.com"))),
                new RoleAssignment("2", "admin", new Subject.ClaimsPredicate("$.email", Rule.Function.EQUAL, List.of("bob@acme.com"))));

        assertEquals(Set.of("editor"), OrgUnitSubjects.heldRoles(bob, assignments));
    }
}
