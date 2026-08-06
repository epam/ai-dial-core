package com.epam.aidial.core.server.security;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.Rule;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

class RuleMatcherTest {

    @Test
    void testUserRoleRules() {

        // test access by access token

        verifyOnAccessToken(rule("roles", Rule.Function.TRUE), true, "any-role");
        verifyOnAccessToken(rule("roles", Rule.Function.FALSE), false, "any-role");

        verifyOnAccessToken(rule("roles", Rule.Function.EQUAL, "admin"), true, "admin");
        verifyOnAccessToken(rule("roles", Rule.Function.EQUAL, "admin1"), false, "admin");
        verifyOnAccessToken(rule("roles", Rule.Function.EQUAL, "admin1"), false, "admin2");
        verifyOnAccessToken(rule("roles", Rule.Function.EQUAL, "AdMin"), true, "admin");

        verifyOnAccessToken(rule("roles", Rule.Function.CONTAIN, "admin"), true, "admin");
        verifyOnAccessToken(rule("roles", Rule.Function.CONTAIN, "admin1"), false, "admin");
        verifyOnAccessToken(rule("roles", Rule.Function.CONTAIN, "admin"), true, "admin2");
        verifyOnAccessToken(rule("roles", Rule.Function.CONTAIN, "dmi"), true, "admin");
        verifyOnAccessToken(rule("roles", Rule.Function.CONTAIN, "manager"), true, "Delivery Manager");

        verifyOnAccessToken(rule("roles", Rule.Function.REGEX, ".*"), true, "any");
        verifyOnAccessToken(rule("roles", Rule.Function.REGEX, "(admin|user)"), true, "user");
        verifyOnAccessToken(rule("roles", Rule.Function.REGEX, "(admin|user)$"), false, "user2");

        // test access by API key

        verifyOnApiKey(rule("roles", Rule.Function.TRUE), true, "any-role");
        verifyOnApiKey(rule("roles", Rule.Function.FALSE), false, "any-role");

        verifyOnApiKey(rule("roles", Rule.Function.EQUAL, "admin"), true, "admin");
        verifyOnApiKey(rule("roles", Rule.Function.EQUAL, "admin1"), false, "admin");
        verifyOnApiKey(rule("roles", Rule.Function.EQUAL, "admin1"), false, "admin2");
        verifyOnApiKey(rule("roles", Rule.Function.EQUAL, "AdMin"), true, "admin");

        verifyOnApiKey(rule("roles", Rule.Function.CONTAIN, "admin"), true, "admin");
        verifyOnApiKey(rule("roles", Rule.Function.CONTAIN, "admin1"), false, "admin");
        verifyOnApiKey(rule("roles", Rule.Function.CONTAIN, "admin"), true, "admin2");
        verifyOnApiKey(rule("roles", Rule.Function.CONTAIN, "dmi"), true, "admin");
        verifyOnApiKey(rule("roles", Rule.Function.CONTAIN, "manager"), true, "Delivery Manager");

        verifyOnApiKey(rule("roles", Rule.Function.REGEX, ".*"), true, "any");
        verifyOnApiKey(rule("roles", Rule.Function.REGEX, "(admin|user)"), true, "user");
        verifyOnApiKey(rule("roles", Rule.Function.REGEX, "(admin|user)$"), false, "user2");
    }

    @Test
    void testNestedUserClaimRules() {
        // test access by access token

        verifyOnAccessToken(List.of(rule("job.title", Rule.Function.TRUE)),
                List.of("user1"), Map.of("job", Map.of("title", List.of("Software Engineer"))), true);
        verifyOnAccessToken(List.of(rule("job.title", Rule.Function.FALSE)),
                List.of("user2"), Map.of("job", Map.of("title", List.of("Software Engineer"))), false);

        verifyOnAccessToken(List.of(rule("job.title", Rule.Function.EQUAL, "Software Engineer")),
                List.of("admin"), Map.of("job", Map.of("title", List.of("Software Engineer"))), true);
        verifyOnAccessToken(List.of(rule("job.title", Rule.Function.EQUAL, "Engineer")),
                List.of("user"), Map.of("job", Map.of("title", List.of("Software Engineer"))), false);
    }

    @Test
    void testUserClaimRules() {
        // test access by access token

        verifyOnAccessToken(List.of(rule("title", Rule.Function.TRUE)),
                List.of("user1"), Map.of("title", List.of("Software Engineer")), true);
        verifyOnAccessToken(List.of(rule("title", Rule.Function.FALSE)),
                List.of("user2"), Map.of("title", List.of("Software Engineer")), false);

        verifyOnAccessToken(List.of(rule("title", Rule.Function.EQUAL, "Software Engineer")),
                List.of("admin"), Map.of("title", List.of("Software Engineer")), true);
        verifyOnAccessToken(List.of(rule("title", Rule.Function.EQUAL, "Engineer")),
                List.of("user"), Map.of("title", List.of("Software Engineer")), false);

        verifyOnAccessToken(List.of(rule("email", Rule.Function.CONTAIN, "@example.com")),
                List.of("admin"), Map.of("email", List.of("foo_bar@example.com")), true);
        verifyOnAccessToken(List.of(rule("email", Rule.Function.CONTAIN, "@example.com")),
                List.of("user"), Map.of("email", List.of("foo_bar@mail.com")), false);
        verifyOnAccessToken(List.of(rule("email", Rule.Function.CONTAIN, "@example.com")),
                List.of("user"), Map.of(), false);

        verifyOnAccessToken(List.of(rule("title", Rule.Function.REGEX, ".*")),
                List.of("admin"), Map.of("title", List.of("Developer")), true);
        verifyOnAccessToken(List.of(rule("title", Rule.Function.REGEX, "(Developer|Manager)")),
                List.of("user"), Map.of("title", List.of("Manager")), true);
        verifyOnAccessToken(List.of(rule("title", Rule.Function.REGEX, ".*(Manager|Developer)$")),
                List.of("user"), Map.of("title", List.of("Senior Delivery Manager")), true);
        verifyOnAccessToken(List.of(rule("title", Rule.Function.REGEX, ".*(Manager|Developer)$")),
                List.of("user"), Map.of("title", List.of("Manager Senior")), false);

        // test access by API key

        verifyOnApiKey(List.of(rule("title", Rule.Function.TRUE)),
                List.of("user1"), false);

        verifyOnApiKey(List.of(rule("title", Rule.Function.EQUAL, "Software Engineer")),
                List.of("admin"), false);

        verifyOnApiKey(List.of(rule("email", Rule.Function.CONTAIN, "@example.com")),
                List.of("admin"), false);

        verifyOnApiKey(List.of(rule("title", Rule.Function.REGEX, ".*")),
                List.of("admin"), false);
    }

    @Test
    void testRuleWithInvalidSourcePath_shouldNotThrow() {
        // Rule source with special characters causes InvalidPathException in jayway JsonPath.
        // Should be handled gracefully (no match) instead of crashing with 500.
        verifyOnAccessToken(List.of(rule("custom claim", Rule.Function.EQUAL, "some-value")),
                List.of("user"), Map.of("title", List.of("Engineer")), false);

        verifyOnAccessToken(List.of(rule("user group", Rule.Function.TRUE)),
                List.of("user"), Map.of("user group", List.of("value")), false);

        verifyOnAccessToken(List.of(rule("department name", Rule.Function.CONTAIN, "Engineering")),
                List.of("user"), Map.of("department name", List.of("Software Engineering")), false);
    }

    @Test
    void testCombinedRules() {
        // test access by access token

        verifyOnAccessToken(List.of(rule("title", Rule.Function.TRUE), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("user1"), Map.of("title", List.of("Software Engineer")), true);
        verifyOnAccessToken(List.of(rule("title", Rule.Function.TRUE), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("dial"), Map.of(), true);
        verifyOnAccessToken(List.of(rule("title", Rule.Function.CONTAIN, "Software"), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("custom"), Map.of("title", List.of("System Engineer")), false);

        verifyOnAccessToken(List.of(rule("title", Rule.Function.EQUAL, "Software Engineer"), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("admin"), Map.of("title", List.of("Software Engineer")), true);
        verifyOnAccessToken(List.of(rule("title", Rule.Function.EQUAL, "Software Engineer"), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("dial"), Map.of("title", List.of("Manager")), true);
        verifyOnAccessToken(List.of(rule("title", Rule.Function.EQUAL, "Engineer"),  rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("user"), Map.of("title", List.of("Software Engineer")), false);

        verifyOnAccessToken(List.of(rule("email", Rule.Function.CONTAIN, "@example.com"), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("admin"), Map.of("email", List.of("foo_bar@example.com")), true);
        verifyOnAccessToken(List.of(rule("email", Rule.Function.CONTAIN, "@example.com"), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("dial"), Map.of("email", List.of("foo_bar@example2.com")), true);
        verifyOnAccessToken(List.of(rule("email", Rule.Function.CONTAIN, "@example.com"), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("user"), Map.of("email", List.of("foo_bar@mail.com")), false);
        verifyOnAccessToken(List.of(rule("email", Rule.Function.CONTAIN, "@example.com"), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("user"), Map.of(), false);

        verifyOnAccessToken(List.of(rule("title", Rule.Function.REGEX, ".*"), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("admin"), Map.of("title", List.of("Developer")), true);
        verifyOnAccessToken(List.of(rule("title", Rule.Function.REGEX, "^Developer$"), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("dial"), Map.of("title", List.of("Manager")), true);
        verifyOnAccessToken(List.of(rule("title", Rule.Function.REGEX, "(Developer|Manager)"), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("dial"), Map.of("title", List.of("Human Resource")), true);
        verifyOnAccessToken(List.of(rule("title", Rule.Function.REGEX, ".*(Manager|Developer)$"), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("user"), Map.of("title", List.of("Senior Delivery Manager")), true);
        verifyOnAccessToken(List.of(rule("title", Rule.Function.REGEX, ".*(Manager|Developer)$"), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("user"), Map.of("title", List.of("Manager Senior")), false);

        // test access by API key

        verifyOnApiKey(List.of(rule("title", Rule.Function.TRUE), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("user1"), false);


        verifyOnApiKey(List.of(rule("title", Rule.Function.EQUAL, "Software Engineer"), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("admin"), false);

        verifyOnApiKey(List.of(rule("email", Rule.Function.CONTAIN, "@example.com"), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("admin"), false);

        verifyOnApiKey(List.of(rule("title", Rule.Function.REGEX, ".*"), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("admin"), false);
    }

    void verifyOnAccessToken(List<Rule> rules, List<String> userRoles, Map<String, Object> userClaims, boolean expected) {
        ProxyContext context = Mockito.mock(ProxyContext.class);
        Mockito.when(context.getUserRoles()).thenReturn(userRoles);
        ObjectNode root = ProxyUtil.MAPPER.valueToTree(userClaims);
        ExtractedClaims claims = new ExtractedClaims("sub", userRoles, "hash", root, null, null, null);
        Mockito.when(context.getExtractedClaims()).thenReturn(claims);
        boolean actual = RuleMatcher.match(context, rules);
        Assertions.assertEquals(expected, actual);
    }

    void verifyOnAccessToken(Rule rule, boolean expected, String... roles) {
        verifyOnAccessToken(List.of(rule), List.of(roles), Map.of(), expected);
    }

    void verifyOnApiKey(List<Rule> rules, List<String> userRoles, boolean expected) {
        ProxyContext context = Mockito.mock(ProxyContext.class);
        Mockito.when(context.getUserRoles()).thenReturn(userRoles);
        boolean actual = RuleMatcher.match(context, rules);
        Assertions.assertEquals(expected, actual);
    }

    void verifyOnApiKey(Rule rule, boolean expected, String... roles) {
        verifyOnApiKey(List.of(rule), List.of(roles), expected);
    }

    Rule rule(String source, Rule.Function function, String... targets) {
        Rule rule = new Rule();
        rule.setSource(source);
        rule.setFunction(function);
        rule.setTargets(List.of(targets));
        return rule;
    }
}
