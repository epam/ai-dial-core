package com.epam.aidial.core.config.policy;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.experimental.UtilityClass;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Checks of {@link Policy} and {@link PolicyAssignment} that need nothing but the record itself,
 * and the overlap test behind the equal-priority rule. Every method returns a message, or {@code null}
 * when the record passes.
 */
@UtilityClass
public class PolicyAssignmentValidator {

    public static final String SUBJECT_PER = "subject";

    /**
     * The subject source of the caller's roles, which unlike most claims holds several values at once.
     */
    public static final String ROLES_SOURCE = "roles";

    private static final String NOT_SUPPORTED = "'%s' is not supported yet";

    /**
     * Fields a policy owns; an assignment carrying one of them is told so instead of getting a bare
     * "unrecognized field".
     */
    private static final List<String> POLICY_ONLY_FIELDS = List.of(
            "priority", "kind", "quantity", "object", "per", "cost", "attachedTo", "harness", "broker", "packages");

    public static String rejectPolicyFieldsOn(JsonNode assignment) {
        for (String field : POLICY_ONLY_FIELDS) {
            if (assignment.has(field)) {
                return "'" + field + "' belongs to the policy, not to an assignment";
            }
        }
        return null;
    }

    public static String validatePolicy(Policy policy) {
        Map<String, JsonNode> reserved = new LinkedHashMap<>();
        reserved.put("attachedTo", policy.getAttachedTo());
        reserved.put("harness", policy.getHarness());
        reserved.put("broker", policy.getBroker());
        reserved.put("packages", policy.getPackages());
        for (Map.Entry<String, JsonNode> field : reserved.entrySet()) {
            if (field.getValue() != null && !field.getValue().isNull()) {
                return NOT_SUPPORTED.formatted(field.getKey());
            }
        }
        if (policy.getKind() == null) {
            return "kind is required";
        }
        if (policy.getQuantity() == null) {
            return "quantity is required";
        }
        if (policy.getQuantity() != PolicyQuantity.COST) {
            return "a budget must have quantity 'cost', got '" + policy.getQuantity().getValue() + "'";
        }
        if (policy.getPriority() == null) {
            return "priority is required for a budget policy";
        }
        if (policy.getObject() == null) {
            return "object is required";
        }
        if (policy.getPer() == null || policy.getPer().isEmpty()) {
            return "per is required";
        }
        if (!policy.getPer().equals(List.of(SUBJECT_PER))) {
            return "per " + policy.getPer() + " is not supported yet, use [\"" + SUBJECT_PER + "\"]";
        }
        String error = validateAmounts(policy.getCost());
        if (error != null) {
            return error;
        }
        return policy.getSubject() == null ? null : validateSubject(policy.getSubject());
    }

    public static String validateAssignment(PolicyAssignment assignment) {
        if (assignment.getPolicy() == null || assignment.getPolicy().isBlank()) {
            return "policy is required";
        }
        if (assignment.getExpiresAt() != null) {
            try {
                OffsetDateTime.parse(assignment.getExpiresAt());
            } catch (DateTimeParseException e) {
                return "expiresAt must be an ISO-8601 date-time with an offset, for example 2027-01-01T00:00:00Z";
            }
        }
        return assignment.getSubject() == null ? null : validateSubject(assignment.getSubject());
    }

    public static String validateSubject(List<PolicySubjectRule> subject) {
        if (subject.isEmpty()) {
            return "subject must have at least one rule; an empty list would match everybody, "
                    + "use {\"source\": \"sub\", \"function\": \"TRUE\"} for that";
        }
        for (PolicySubjectRule rule : subject) {
            if (rule == null || rule.getSource() == null || rule.getSource().isBlank()) {
                return "every subject rule needs a source";
            }
            if (rule.getFunction() == null) {
                return "every subject rule needs a function";
            }
            boolean needsTargets = rule.getFunction() != PolicySubjectRule.Function.TRUE
                    && rule.getFunction() != PolicySubjectRule.Function.FALSE;
            if (needsTargets && (rule.getTargets() == null || rule.getTargets().isEmpty()
                    || rule.getTargets().stream().anyMatch(target -> target == null || target.isEmpty()))) {
                return "subject rule " + rule.getFunction() + " on '" + rule.getSource() + "' needs non-empty targets";
            }
            if (rule.getFunction() == PolicySubjectRule.Function.REGEX) {
                for (String target : rule.getTargets()) {
                    try {
                        Pattern.compile(target);
                    } catch (PatternSyntaxException e) {
                        return "subject rule REGEX on '" + rule.getSource() + "' has an invalid pattern '" + target + "'";
                    }
                }
            }
        }
        return null;
    }

    /**
     * Whether some caller can match both subjects, as far as the rules show. Rules on different sources can
     * always be met by one caller, and so can any two rules on {@code roles}: a caller holds several roles. On one
     * other source, an exact target overlaps the targets equal to it ignoring case, containing it, or matching a
     * pattern in any case; a substring target overlaps any other substring or pattern; and two patterns overlap
     * only when identical, since nothing else is decidable.
     */
    public static boolean subjectsOverlap(List<PolicySubjectRule> first, List<PolicySubjectRule> second) {
        for (PolicySubjectRule a : first) {
            for (PolicySubjectRule b : second) {
                if (rulesOverlap(a, b)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean rulesOverlap(PolicySubjectRule a, PolicySubjectRule b) {
        if (a.getFunction() == PolicySubjectRule.Function.FALSE || b.getFunction() == PolicySubjectRule.Function.FALSE) {
            return false;
        }
        if (!a.getSource().equals(b.getSource()) || ROLES_SOURCE.equals(a.getSource())
                || a.getFunction() == PolicySubjectRule.Function.TRUE || b.getFunction() == PolicySubjectRule.Function.TRUE) {
            return true;
        }
        return switch (a.getFunction()) {
            case EQUAL -> switch (b.getFunction()) {
                case EQUAL -> anyPair(a, b, (x, y) -> x.equalsIgnoreCase(y));
                case CONTAIN -> anyPair(a, b, (x, y) -> lower(x).contains(lower(y)));
                default -> anyPair(a, b, (x, y) -> matchesIgnoringCase(y, x));
            };
            case CONTAIN -> switch (b.getFunction()) {
                case EQUAL -> anyPair(a, b, (x, y) -> lower(y).contains(lower(x)));
                default -> true;
            };
            default -> switch (b.getFunction()) {
                case EQUAL -> anyPair(a, b, (x, y) -> matchesIgnoringCase(x, y));
                case REGEX -> anyPair(a, b, String::equals);
                default -> true;
            };
        };
    }

    private static boolean anyPair(PolicySubjectRule a, PolicySubjectRule b, BiPredicate<String, String> test) {
        return a.getTargets().stream().anyMatch(x -> b.getTargets().stream().anyMatch(y -> test.test(x, y)));
    }

    /**
     * An exact target matches its value in any case, so it overlaps a pattern that matches one of its spellings.
     */
    private static boolean matchesIgnoringCase(String pattern, String value) {
        return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(value).matches();
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static String validateAmounts(PolicyAmounts cost) {
        if (cost == null || (cost.getDay() == null && cost.getMonth() == null)) {
            return "cost must set an amount for at least one window (day, month)";
        }
        if (cost.getDay() != null && cost.getDay().signum() <= 0) {
            return "cost.day must be greater than 0";
        }
        if (cost.getMonth() != null && cost.getMonth().signum() <= 0) {
            return "cost.month must be greater than 0";
        }
        return null;
    }
}
