package com.epam.aidial.core.config;

import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.ApiSchemaType;
import lombok.Data;

/**
 * Each rate is either a flat rate string or a {@link PricingRate} decision tree
 * ({@link com.epam.aidial.core.config.databind.PricingRateSerializer} writes the string form for a leaf), so every
 * rate field declares that union for the OpenAPI spec.
 */
@Data
public class Pricing {
    private String unit;

    @ApiSchema(oneOf = {String.class}, oneOfTypes = {@ApiSchemaType(implementation = PricingRate.class)})
    private PricingRate prompt;

    @ApiSchema(oneOf = {String.class}, oneOfTypes = {@ApiSchemaType(implementation = PricingRate.class)})
    private PricingRate completion;

    @ApiSchema(oneOf = {String.class}, oneOfTypes = {@ApiSchemaType(implementation = PricingRate.class)})
    private PricingRate cacheRead;

    @ApiSchema(oneOf = {String.class}, oneOfTypes = {@ApiSchemaType(implementation = PricingRate.class)})
    private PricingRate cacheWrite;
}
