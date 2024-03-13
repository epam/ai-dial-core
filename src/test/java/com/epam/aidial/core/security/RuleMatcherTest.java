package com.epam.aidial.core.security;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.Rule;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

class RuleMatcherTest {

    @Test
    void testRules() {
        verify(rule("roles", Rule.Function.TRUE), true, "any-role");
        verify(rule("roles", Rule.Function.FALSE), false, "any-role");

        verify(rule("roles", Rule.Function.EQUAL, "admin"), true, "admin");
        verify(rule("roles", Rule.Function.EQUAL, "admin1"), false, "admin");
        verify(rule("roles", Rule.Function.EQUAL, "admin1"), false, "admin2");

        verify(rule("roles", Rule.Function.CONTAIN, "admin"), true, "admin");
        verify(rule("roles", Rule.Function.CONTAIN, "admin1"), false, "admin");
        verify(rule("roles", Rule.Function.CONTAIN, "admin"), true, "admin2");
        verify(rule("roles", Rule.Function.CONTAIN, "dmi"), true, "admin");

        verify(rule("roles", Rule.Function.REGEX, ".*"), true, "any");
        verify(rule("roles", Rule.Function.REGEX, "(admin|user)"), true, "user");
        verify(rule("roles", Rule.Function.REGEX, "(admin|user)$"), false, "user2");
    }

    void verify(Rule rule, boolean expected, String... roles) {
        ProxyContext context = Mockito.mock(ProxyContext.class);
        ExtractedClaims claims = new ExtractedClaims("sub", List.of(roles), "hash");
        Mockito.when(context.getExtractedClaims()).thenReturn(claims);
        boolean actual = RuleMatcher.match(context, List.of(rule));
        Assertions.assertEquals(expected, actual);
    }

    Rule rule(String source, Rule.Function function, String... targets) {
        Rule rule = new Rule();
        rule.setSource(source);
        rule.setFunction(function);
        rule.setTargets(List.of(targets));
        return rule;
    }
}
