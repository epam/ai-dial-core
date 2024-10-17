package com.epam.aidial.core.security;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.Rule;
import lombok.experimental.UtilityClass;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@UtilityClass
public class RuleMatcher {

    /**
     *
     * @return true if any of the provided rule matched (OR condition behaviour), otherwise - false
     */
    public boolean match(ProxyContext context, Collection<Rule> rules) {
        // if no rules provided - resource is available to everybody
        if (rules.isEmpty()) {
            return true;
        }

        ExtractedClaims claims = context.getExtractedClaims();
        if (claims == null) {
            return false;
        }

        Map<String, List<String>> userClaims = claims.userClaims();

        for (Rule rule : rules) {
            String targetClaim = rule.getSource();
            List<String> sources;
            if (targetClaim.equals("roles")) {
                sources = claims.userRoles();
            } else {
                sources = userClaims.get(targetClaim);
            }

            if (sources == null) {
                continue;
            }

            List<String> targets = rule.getTargets();
            boolean match = switch (rule.getFunction()) {
                case TRUE -> true;
                case FALSE -> false;
                case EQUAL -> equal(sources, targets);
                case CONTAIN -> contain(sources, targets);
                case REGEX -> regex(sources, targets);
            };

            if (match) {
                return true;
            }
        }

        return false;
    }

    private boolean equal(List<String> sources, List<String> targets) {
        for (String target : targets) {
            for (String source : sources) {
                if (source.equalsIgnoreCase(target)) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean contain(List<String> sources, List<String> targets) {
        for (String target : targets) {
            for (String role : sources) {
                if (role.toLowerCase().contains(target.toLowerCase())) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean regex(List<String> sources, List<String> targets) {
        for (String target : targets) {
            Pattern pattern = Pattern.compile(target);
            for (String role : sources) {
                if (pattern.matcher(role).matches()) {
                    return true;
                }
            }
        }

        return false;
    }
}