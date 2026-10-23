package com.epam.aidial.core.server.service.policy;

import com.epam.aidial.core.config.policy.Policy;
import com.epam.aidial.core.config.policy.PolicyAssignment;
import com.epam.aidial.core.config.policy.PolicyKind;
import com.epam.aidial.core.config.policy.PolicyQuantity;
import com.epam.aidial.core.config.policy.PolicySubjectRule;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyRecordCodecTest {

    private static JsonNode json(String text) throws Exception {
        return ProxyUtil.MAPPER.readTree(text);
    }

    private static String parseError(String policy) throws Exception {
        return assertThrows(IllegalArgumentException.class, () -> PolicyRecordCodec.parsePolicy(json(policy))).getMessage();
    }

    @Test
    void testPersonalDefaultBudgetExampleParses() throws Exception {
        Policy policy = PolicyRecordCodec.parsePolicy(json("""
                { "id": "ORG/personal-standard", "priority": 100,
                  "kind": "budget", "quantity": "cost",
                  "object": "*", "per": ["subject"], "cost": { "month": 100 },
                  "subject": [ { "source": "roles", "function": "EQUAL", "targets": ["costLimit-1000"] } ],
                  "costObject": "ORG" }
                """));

        assertEquals("ORG/personal-standard", policy.getId());
        assertEquals(100, policy.getPriority());
        assertEquals(PolicyKind.BUDGET, policy.getKind());
        assertEquals(PolicyQuantity.COST, policy.getQuantity());
        assertTrue(policy.getObject().isAll());
        assertEquals(List.of("subject"), policy.getPer());
        assertEquals(new BigDecimal("100"), policy.getCost().getMonth());
        assertEquals(PolicySubjectRule.Function.EQUAL, policy.getSubject().get(0).getFunction());
        assertEquals("ORG", policy.getCostObject());
        assertTrue(policy.isSelfApplying());
    }

    @Test
    void testGrantExamplesParse() throws Exception {
        Policy policy = PolicyRecordCodec.parsePolicy(json("""
                { "id": "anthropic-pro-500", "priority": 10,
                  "kind": "budget", "quantity": "cost",
                  "object": ["opus", "sonnet"], "per": ["subject"], "cost": { "month": 500 } }
                """));
        PolicyAssignment assignment = PolicyRecordCodec.parseAssignment(json("""
                { "id": "project-a/grant-1",
                  "policy": "anthropic-pro-500",
                  "subject": [ { "source": "sub", "function": "EQUAL", "targets": ["alice"] } ],
                  "costObject": "project-a" }
                """));

        assertFalse(policy.getObject().isAll());
        assertTrue(policy.getObject().contains("opus"));
        assertFalse(policy.isSelfApplying());
        assertEquals("anthropic-pro-500", assignment.getPolicy());
        assertEquals("project-a", assignment.getCostObject());
    }

    @Test
    void testEveryoneSubjectNeedsNoTargets() throws Exception {
        PolicyAssignment assignment = PolicyRecordCodec.parseAssignment(json("""
                { "policy": "p", "subject": [ { "source": "sub", "function": "TRUE" } ] }
                """));

        assertEquals(PolicySubjectRule.Function.TRUE, assignment.getSubject().get(0).getFunction());
    }

    @Test
    void testRoundTripKeepsTheDocument() throws Exception {
        String source = """
                {"id":"gpt-500","priority":1,"kind":"budget","quantity":"cost","object":["gpt"],\
                "per":["subject"],"cost":{"day":5,"month":500}}""";

        Policy policy = PolicyRecordCodec.parsePolicy(json(source));

        assertEquals(json(source), json(PolicyRecordCodec.write(policy)));
        assertEquals(policy, PolicyRecordCodec.readPolicy(PolicyRecordCodec.write(policy)));
    }

    @Test
    void testUnknownFieldIsRejected() throws Exception {
        assertEquals("Failed to parse Policy at \"colour\": unrecognized field", parseError("""
                {"priority": 1, "colour": "red"}"""));
        assertTrue(parseError("""
                {"priority": 1, "cost": {"week": 5}}""").contains("\"cost.week\": unrecognized field"));
        assertThrows(IllegalArgumentException.class, () -> PolicyRecordCodec.parseAssignment(json("""
                {"policy": "p", "colour": "red"}""")));
    }

    @Test
    void testPriorityMustBeAnInteger() throws Exception {
        assertTrue(parseError("""
                {"priority": 10.5}""").startsWith("Failed to parse Policy at \"priority\""));
        assertTrue(parseError("""
                {"priority": 10.0}""").startsWith("Failed to parse Policy at \"priority\""));
        assertTrue(parseError("""
                {"priority": "10"}""").startsWith("Failed to parse Policy at \"priority\""));
    }

    @Test
    void testUnknownEnumValuesAreRejected() throws Exception {
        assertTrue(parseError("""
                {"kind": "cap"}""").startsWith("Failed to parse Policy at \"kind\""));
        assertTrue(parseError("""
                {"quantity": "tokens-per-fortnight"}""").startsWith("Failed to parse Policy at \"quantity\""));
        assertThrows(IllegalArgumentException.class, () -> PolicyRecordCodec.parseAssignment(json("""
                {"policy": "p", "subject": [{"source": "sub", "function": "STARTS_WITH", "targets": ["a"]}]}""")));
    }

    @Test
    void testObjectMustBeWildcardOrList() throws Exception {
        assertTrue(parseError("""
                {"object": "opus"}""").contains("object must be"));
        assertTrue(parseError("""
                {"object": []}""").contains("object must not be an empty list"));
        assertTrue(parseError("""
                {"object": ["*"]}""").contains("use \"*\""));
    }

    @Test
    void testAssignmentNamesTheFieldThatBelongsToThePolicy() throws Exception {
        String message = assertThrows(IllegalArgumentException.class, () -> PolicyRecordCodec.parseAssignment(json("""
                {"policy": "p", "priority": 5}"""))).getMessage();

        assertEquals("Failed to parse PolicyAssignment: 'priority' belongs to the policy, not to an assignment", message);
    }

    @Test
    void testBodyMustBeAnObject() throws Exception {
        assertEquals("Failed to parse Policy: a JSON object is expected", parseError("[]"));
        assertThrows(IllegalArgumentException.class, () -> PolicyRecordCodec.parseAssignment(json("\"text\"")));
    }

    @Test
    void testMalformedStoredRecordIsAnError() {
        assertThrows(IllegalStateException.class, () -> PolicyRecordCodec.readPolicy("{broken"));
        assertThrows(IllegalStateException.class, () -> PolicyRecordCodec.readAssignment("{\"unknown\": 1}"));
    }
}
