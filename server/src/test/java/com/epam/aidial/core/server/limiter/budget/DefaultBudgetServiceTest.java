package com.epam.aidial.core.server.limiter.budget;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.policy.BudgetInstance;
import com.epam.aidial.core.config.policy.CostObjectIndex;
import com.epam.aidial.core.config.policy.Policy;
import com.epam.aidial.core.config.policy.PolicyAssignment;
import com.epam.aidial.core.config.policy.PolicyKind;
import com.epam.aidial.core.config.policy.PolicyObjectSet;
import com.epam.aidial.core.config.policy.PolicyQuantity;
import com.epam.aidial.core.config.policy.PolicySubjectRule;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultBudgetServiceTest {

    private static final String HELD = "projA/alice";
    private static final String OTHER = "projA/bob";
    private static final String EXPIRED = "projA/expired";

    private ResourceService resourceService;
    private ProxyContext context;
    private DefaultBudgetService service;

    @BeforeEach
    void setUp() {
        resourceService = mock(ResourceService.class);
        context = mock(ProxyContext.class);
        Policy policy = new Policy();
        policy.setKind(PolicyKind.BUDGET);
        policy.setQuantity(PolicyQuantity.COST);
        policy.setObject(PolicyObjectSet.all());
        Config config = new Config();
        config.setCostObjectIndex(CostObjectIndex.build(Map.of("p", policy), Map.of(
                HELD, assignment("alice", null),
                OTHER, assignment("bob", null),
                EXPIRED, assignment("alice", Instant.now().minusSeconds(60).toString())), Map.of()));
        when(context.getConfig()).thenReturn(config);
        when(context.getUserId()).thenReturn("alice");
        when(context.getExtractedClaims()).thenReturn(claims("alice"));
        service = new DefaultBudgetService(resourceService);
    }

    @Test
    void testNothingStored() {
        assertNull(service.findDefault(context));
    }

    @Test
    void testStoredNull() {
        stored("{\"budget\":null}");

        assertNull(service.findDefault(context));
    }

    @Test
    void testStoredHeldBudget() {
        stored("{\"budget\":\"" + HELD + "\"}");

        BudgetInstance budget = service.findDefault(context);

        assertEquals(HELD, budget.id());
    }

    @Test
    void testStoredBudgetOfAnotherSubject() {
        stored("{\"budget\":\"" + OTHER + "\"}");

        assertNull(service.findDefault(context));
    }

    @Test
    void testStoredExpiredBudget() {
        stored("{\"budget\":\"" + EXPIRED + "\"}");

        assertNull(service.findDefault(context));
    }

    @Test
    void testStoredWithdrawnBudget() {
        stored("{\"budget\":\"projA/gone\"}");

        assertNull(service.findDefault(context));
    }

    @Test
    void testSetHeldBudget() {
        assertTrue(service.setDefault(context, HELD));

        ArgumentCaptor<ResourceDescriptor> descriptor = ArgumentCaptor.forClass(ResourceDescriptor.class);
        verify(resourceService).putResource(descriptor.capture(), eq("{\"budget\":\"" + HELD + "\"}"), eq(EtagHeader.ANY));
        assertRecord(descriptor.getValue());
    }

    @Test
    void testSetBudgetNotHeld() {
        assertFalse(service.setDefault(context, OTHER));
        assertFalse(service.setDefault(context, EXPIRED));

        verify(resourceService, never()).putResource(any(ResourceDescriptor.class), anyString(), any(EtagHeader.class));
    }

    @Test
    void testClear() {
        service.clearDefault(context);

        ArgumentCaptor<ResourceDescriptor> descriptor = ArgumentCaptor.forClass(ResourceDescriptor.class);
        verify(resourceService).deleteResource(descriptor.capture(), eq(EtagHeader.ANY));
        assertRecord(descriptor.getValue());
    }

    private void stored(String json) {
        when(resourceService.getResource(any(ResourceDescriptor.class))).thenReturn(json);
    }

    private static void assertRecord(ResourceDescriptor descriptor) {
        assertEquals(ResourceTypes.USER_BUDGET_DEFAULT, descriptor.getType());
        assertEquals("Users/alice/", descriptor.getBucketLocation());
        assertEquals("default", descriptor.getName());
    }

    private static PolicyAssignment assignment(String sub, String expiresAt) {
        PolicySubjectRule rule = new PolicySubjectRule();
        rule.setSource("sub");
        rule.setFunction(PolicySubjectRule.Function.EQUAL);
        rule.setTargets(List.of(sub));
        PolicyAssignment assignment = new PolicyAssignment();
        assignment.setPolicy("p");
        assignment.setSubject(List.of(rule));
        assignment.setExpiresAt(expiresAt);
        return assignment;
    }

    private static ExtractedClaims claims(String sub) {
        ObjectNode claims = ProxyUtil.MAPPER.createObjectNode();
        claims.put("sub", sub);
        return new ExtractedClaims(sub, List.of("staff"), "hash", claims, null, sub, null);
    }
}
