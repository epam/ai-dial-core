package com.epam.aidial.core.server.orgunit;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.Rule;
import com.epam.aidial.core.server.security.RuleMatcher;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Who a role assignment's subject is. Signed-in users hold roles in a unit. An application acting for a user reads
 * through that user's roles, and an API key reads through a subject on {@code roles} its configured roles match; neither
 * writes, approves or manages roles through them.
 */
public final class OrgUnitSubjects {

    private OrgUnitSubjects() {
    }

    public static boolean isPerson(ProxyContext context) {
        return context.getExtractedClaims() != null && context.getApiKeyData().getPerRequestKey() == null;
    }

    /**
     * The role names of the assignments whose subject is the caller; none for a caller who is not a person.
     */
    public static Set<String> heldRoles(ProxyContext context, List<RoleAssignment> assignments) {
        return isPerson(context) ? matching(context, assignments) : new HashSet<>();
    }

    /**
     * The role names of the assignments whose subject is the caller, a person or not: what the caller reads through.
     */
    public static Set<String> rolesToReadWith(ProxyContext context, List<RoleAssignment> assignments) {
        return matching(context, assignments);
    }

    private static Set<String> matching(ProxyContext context, List<RoleAssignment> assignments) {
        Set<String> held = new HashSet<>();
        for (RoleAssignment assignment : assignments) {
            if (!held.contains(assignment.roleName()) && matches(context, assignment.subject())) {
                held.add(assignment.roleName());
            }
        }
        return held;
    }

    /**
     * Only claims predicates with an {@code EQUAL} on at least one target, or a {@code TRUE} with none, match, evaluated
     * as a read rule is: case-insensitive, on the same claim values, and a {@code TRUE} for any caller whose token has
     * the claim, on a source that is a plain claim name or path. Anything else stored grants nothing, so a document
     * written out of band can't open a unit to everyone or run a JSON path filter on every check.
     */
    private static boolean matches(ProxyContext context, Subject subject) {
        if (!(subject instanceof Subject.ClaimsPredicate predicate)) {
            return false;
        }
        if (!OrgUnitService.isClaimPath(predicate.source())) {
            return false;
        }
        boolean hasTargets = predicate.targets() != null && !predicate.targets().isEmpty();
        boolean valid = switch (predicate.function()) {
            case EQUAL -> hasTargets;
            case TRUE -> !hasTargets;
            default -> false;
        };
        if (!valid) {
            return false;
        }

        Rule rule = new Rule();
        rule.setSource(predicate.source());
        rule.setFunction(predicate.function());
        rule.setTargets(predicate.targets());
        return RuleMatcher.match(context, List.of(rule));
    }
}
