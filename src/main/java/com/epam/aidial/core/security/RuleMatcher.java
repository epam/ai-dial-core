package com.epam.aidial.core.security;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.Rule;
import lombok.experimental.UtilityClass;

import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

@UtilityClass
public class RuleMatcher {

    public boolean match(ProxyContext context, Collection<Rule> rules) {
        ExtractedClaims claims = context.getExtractedClaims();
        if (claims == null) {
            return false;
        }

        List<String> roles = claims.userRoles();
        if (roles == null || roles.isEmpty() || rules == null || rules.isEmpty()) {
            return false;
        }

        for (Rule rule : rules) {
            if (!rule.getSource().equals("roles")) {
                return false;
            }

            List<String> targets = rule.getTargets();
            boolean match = switch (rule.getFunction()) {
                case TRUE -> true;
                case FALSE -> false;
                case EQUAL -> equal(roles, targets);
                case CONTAIN -> contain(roles, targets);
                case REGEX -> regex(roles, targets);
            };

            if (!match) {
                return false;
            }
        }

        return true;
    }

    private boolean equal(List<String> roles, List<String> targets) {
        for (String target : targets) {
            if (roles.contains(target)) {
                return true;
            }
        }

        return false;
    }

    private boolean contain(List<String> roles, List<String> targets) {
        for (String target : targets) {
            for (String role : roles) {
                if (role.contains(target)) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean regex(List<String> roles, List<String> targets) {
        for (String target : targets) {
            Pattern pattern = Pattern.compile(target);
            for (String role : roles) {
                if (pattern.matcher(role).matches()) {
                    return true;
                }
            }
        }

        return false;
    }
}