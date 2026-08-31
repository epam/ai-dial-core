package com.epam.aidial.core.server.pricing;

import com.epam.aidial.core.config.Condition;
import com.epam.aidial.core.config.Operator;
import com.epam.aidial.core.config.PricingRate;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.experimental.UtilityClass;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Walks a {@link PricingRate} decision tree against a {@link UsageEvalContext}, resolving to the
 * matched leaf's rate string. A missing/unresolvable field (requirement 8) or a type-mismatched
 * ordering comparison (requirement 9) both degrade to a non-match rather than an error; an omitted
 * branch (requirement 2) surfaces as an empty result so the caller can fall back to {@code promptRate}.
 */
@UtilityClass
public class PricingRateEvaluator {

    public Optional<String> evaluate(PricingRate pricingRate, UsageEvalContext ctx) {
        if (pricingRate.isLeaf()) {
            return Optional.of(pricingRate.getRate());
        }

        boolean matched = test(pricingRate.getTest(), ctx);
        PricingRate branch = matched ? pricingRate.getIfTrue() : pricingRate.getIfFalse();
        return branch == null ? Optional.empty() : evaluate(branch, ctx);
    }

    private boolean test(Condition condition, UsageEvalContext ctx) {
        Optional<JsonNode> resolved = ctx.resolve(condition.getField());
        return resolved.isPresent() && compare(resolved.get(), condition.getOperator(), condition.getValue());
    }

    private boolean compare(JsonNode actual, Operator operator, Object expected) {
        if (operator == Operator.EQ || operator == Operator.NE) {
            boolean equal = actual.asText().equals(String.valueOf(expected));
            return operator == Operator.EQ ? equal : !equal;
        }

        BigDecimal expectedNum = toNumeric(expected);
        if (!actual.isNumber() || expectedNum == null) {
            return false;
        }

        int cmp = new BigDecimal(actual.asText()).compareTo(expectedNum);
        return switch (operator) {
            case GT -> cmp > 0;
            case LT -> cmp < 0;
            case GE -> cmp >= 0;
            case LE -> cmp <= 0;
            default -> false;
        };
    }

    private BigDecimal toNumeric(Object value) {
        try {
            return value instanceof Number number ? new BigDecimal(number.toString()) : new BigDecimal((String) value);
        } catch (NumberFormatException | ClassCastException e) {
            return null;
        }
    }
}
