package com.epam.aidial.core.config;

import com.epam.aidial.core.config.databind.PricingRateDeserializer;
import com.epam.aidial.core.config.databind.PricingRateSerializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Data;

/**
 * A per-token pricing rate that is either a flat rate (the {@code rate} leaf shape) or a decision
 * tree that resolves to a rate based on a {@link Condition} evaluated against the active call's
 * usage data. Used for all four of {@code Pricing.prompt}/{@code completion}/{@code cacheRead}/
 * {@code cacheWrite} - nothing in its shape is specific to any one of them.
 *
 * <p>Deliberately carries no class-level {@code @ApiSchema}: the string-or-tree union is declared
 * at each field that uses this type (see {@code Pricing.prompt}/{@code completion}/
 * {@code cacheRead}/{@code cacheWrite}), so this class's own bean shape (including the recursive
 * {@code ifTrue}/{@code ifFalse} fields) still reflects normally wherever it's referenced as the
 * object alternative of that union.
 */
@Data
@JsonDeserialize(using = PricingRateDeserializer.class)
@JsonSerialize(using = PricingRateSerializer.class)
public class PricingRate {

    // leaf shape
    private String rate;

    // node shape; ifTrue/ifFalse omitted -> falls back to promptRate
    private Condition test;
    private PricingRate ifTrue;
    private PricingRate ifFalse;

    public boolean isLeaf() {
        return rate != null;
    }

    public static PricingRate flat(String rate) {
        PricingRate pricingRate = new PricingRate();
        pricingRate.setRate(rate);
        return pricingRate;
    }
}
