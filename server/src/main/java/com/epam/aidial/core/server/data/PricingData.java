package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.PricingRate;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.ApiSchemaType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class PricingData {
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
