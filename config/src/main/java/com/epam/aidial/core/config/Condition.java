package com.epam.aidial.core.config;

import com.epam.aidial.core.config.validation.ValidCondition;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * A single test in a pricing decision tree: {@code field <operator> value}. {@code field} is
 * either a bare standard-field name (see {@link StandardField}) or a {@code $}-prefixed JSON Path
 * expression (RFC 9535) evaluated against the active call's usage data.
 */
@Data
@ValidCondition
public class Condition {

    @NotNull
    private String field;

    @NotNull
    private Operator operator;

    @NotNull
    private Object value;
}
