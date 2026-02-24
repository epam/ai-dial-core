package com.epam.aidial.core.server.security;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.Rule;
import com.epam.aidial.core.server.util.JsonPath;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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

        JsonNode userClaims = Optional.ofNullable(context.getExtractedClaims())
                .map(ExtractedClaims::userClaims).orElse(null);

        for (Rule rule : rules) {
            String claim = rule.getSource();
            List<String> sources;
            if (claim.equals("roles")) {
                sources = context.getUserRoles();
            } else {
                sources = userClaims == null ? null : extract(userClaims, claim);
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

    private List<String> extract(JsonNode claims, String path) {
        JsonNode node = JsonPath.read(claims, path);
        if (node == null) {
            return null;
        }
        if (node.isTextual()) {
            return List.of(node.textValue());
        } else if (node.isArray()) {
            List<String> list = new ArrayList<>();
            for (int i = 0; i < node.size(); i++) {
                JsonNode child = node.get(i);
                if (child.isTextual()) {
                    list.add(child.textValue());
                }
            }
            return list;
        }
        return null;
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