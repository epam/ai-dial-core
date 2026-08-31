package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * Comparison operator for a pricing decision-tree {@link Condition}.
 */
@Getter
public enum Operator {

    EQ("=="),
    NE("!="),
    GT(">"),
    LT("<"),
    GE(">="),
    LE("<=");

    @JsonValue
    private final String symbol;

    Operator(String symbol) {
        this.symbol = symbol;
    }

    public boolean isOrdering() {
        return this == GT || this == LT || this == GE || this == LE;
    }
}
