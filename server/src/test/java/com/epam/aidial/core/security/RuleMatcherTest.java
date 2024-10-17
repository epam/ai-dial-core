package com.epam.aidial.core.security;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.Rule;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

class RuleMatcherTest {

    @Test
    void testUserRoleRules() {
        verify(rule("roles", Rule.Function.TRUE), true, "any-role");
        verify(rule("roles", Rule.Function.FALSE), false, "any-role");

        verify(rule("roles", Rule.Function.EQUAL, "admin"), true, "admin");
        verify(rule("roles", Rule.Function.EQUAL, "admin1"), false, "admin");
        verify(rule("roles", Rule.Function.EQUAL, "admin1"), false, "admin2");
        verify(rule("roles", Rule.Function.EQUAL, "AdMin"), true, "admin");

        verify(rule("roles", Rule.Function.CONTAIN, "admin"), true, "admin");
        verify(rule("roles", Rule.Function.CONTAIN, "admin1"), false, "admin");
        verify(rule("roles", Rule.Function.CONTAIN, "admin"), true, "admin2");
        verify(rule("roles", Rule.Function.CONTAIN, "dmi"), true, "admin");
        verify(rule("roles", Rule.Function.CONTAIN, "manager"), true, "Delivery Manager");

        verify(rule("roles", Rule.Function.REGEX, ".*"), true, "any");
        verify(rule("roles", Rule.Function.REGEX, "(admin|user)"), true, "user");
        verify(rule("roles", Rule.Function.REGEX, "(admin|user)$"), false, "user2");
    }

    @Test
    void testUserClaimRules() {
        verify(List.of(rule("title", Rule.Function.TRUE)),
                List.of("user1"), Map.of("title", List.of("Software Engineer")), true);
        verify(List.of(rule("title", Rule.Function.FALSE)),
                List.of("user2"), Map.of("title", List.of("Software Engineer")), false);

        verify(List.of(rule("title", Rule.Function.EQUAL, "Software Engineer")),
                List.of("admin"), Map.of("title", List.of("Software Engineer")), true);
        verify(List.of(rule("title", Rule.Function.EQUAL, "Engineer")),
                List.of("user"), Map.of("title", List.of("Software Engineer")), false);

        verify(List.of(rule("email", Rule.Function.CONTAIN, "@example.com")),
                List.of("admin"), Map.of("email", List.of("foo_bar@example.com")), true);
        verify(List.of(rule("email", Rule.Function.CONTAIN, "@example.com")),
                List.of("user"), Map.of("email", List.of("foo_bar@mail.com")), false);
        verify(List.of(rule("email", Rule.Function.CONTAIN, "@example.com")),
                List.of("user"), Map.of(), false);

        verify(List.of(rule("title", Rule.Function.REGEX, ".*")),
                List.of("admin"), Map.of("title", List.of("Developer")), true);
        verify(List.of(rule("title", Rule.Function.REGEX, "(Developer|Manager)")),
                List.of("user"), Map.of("title", List.of("Manager")), true);
        verify(List.of(rule("title", Rule.Function.REGEX, ".*(Manager|Developer)$")),
                List.of("user"), Map.of("title", List.of("Senior Delivery Manager")), true);
        verify(List.of(rule("title", Rule.Function.REGEX, ".*(Manager|Developer)$")),
                List.of("user"), Map.of("title", List.of("Manager Senior")), false);
    }

    @Test
    void testCombinedRules() {
        verify(List.of(rule("title", Rule.Function.TRUE), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("user1"), Map.of("title", List.of("Software Engineer")), true);
        verify(List.of(rule("title", Rule.Function.TRUE), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("dial"), Map.of(), true);
        verify(List.of(rule("title", Rule.Function.CONTAIN, "Software"), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("custom"), Map.of("title", List.of("System Engineer")), false);

        verify(List.of(rule("title", Rule.Function.EQUAL, "Software Engineer"), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("admin"), Map.of("title", List.of("Software Engineer")), true);
        verify(List.of(rule("title", Rule.Function.EQUAL, "Software Engineer"), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("dial"), Map.of("title", List.of("Manager")), true);
        verify(List.of(rule("title", Rule.Function.EQUAL, "Engineer"),  rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("user"), Map.of("title", List.of("Software Engineer")), false);

        verify(List.of(rule("email", Rule.Function.CONTAIN, "@example.com"), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("admin"), Map.of("email", List.of("foo_bar@example.com")), true);
        verify(List.of(rule("email", Rule.Function.CONTAIN, "@example.com"), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("dial"), Map.of("email", List.of("foo_bar@example2.com")), true);
        verify(List.of(rule("email", Rule.Function.CONTAIN, "@example.com"), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("user"), Map.of("email", List.of("foo_bar@mail.com")), false);
        verify(List.of(rule("email", Rule.Function.CONTAIN, "@example.com"), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("user"), Map.of(), false);

        verify(List.of(rule("title", Rule.Function.REGEX, ".*"), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("admin"), Map.of("title", List.of("Developer")), true);
        verify(List.of(rule("title", Rule.Function.REGEX, "^Developer$"), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("dial"), Map.of("title", List.of("Manager")), true);
        verify(List.of(rule("title", Rule.Function.REGEX, "(Developer|Manager)"), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("dial"), Map.of("title", List.of("Human Resource")), true);
        verify(List.of(rule("title", Rule.Function.REGEX, ".*(Manager|Developer)$"), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("user"), Map.of("title", List.of("Senior Delivery Manager")), true);
        verify(List.of(rule("title", Rule.Function.REGEX, ".*(Manager|Developer)$"), rule("roles", Rule.Function.EQUAL, "dial")),
                List.of("user"), Map.of("title", List.of("Manager Senior")), false);
    }

    void verify(List<Rule> rules, List<String> userRoles, Map<String, List<String>> userClaims, boolean expected) {
        ProxyContext context = Mockito.mock(ProxyContext.class);
        ExtractedClaims claims = new ExtractedClaims("sub", userRoles, "hash", userClaims);
        Mockito.when(context.getExtractedClaims()).thenReturn(claims);
        boolean actual = RuleMatcher.match(context, rules);
        Assertions.assertEquals(expected, actual);
    }

    void verify(Rule rule, boolean expected, String... roles) {
        verify(List.of(rule), List.of(roles), Map.of(), expected);
    }

    Rule rule(String source, Rule.Function function, String... targets) {
        Rule rule = new Rule();
        rule.setSource(source);
        rule.setFunction(function);
        rule.setTargets(List.of(targets));
        return rule;
    }
}
