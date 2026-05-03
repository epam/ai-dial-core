package com.epam.aidial.core.server.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Value;

/**
 * Single validation warning emitted by {@link ConfigPostProcessor}'s semantic
 * pass when an entity violates a runtime invariant. Surfaced through the
 * {@code validationWarnings} array on Owner-view listing/get responses
 * (design 02 §4.3, 03 §4) and aggregated into the admin health endpoint's
 * {@code skipped[]} reason field.
 */
@Value
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ValidationWarning {
    String field;
    String message;
}
