package com.epam.aidial.core.config;

import com.epam.aidial.core.config.databind.DoubleStringDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;

@Data
public class Pricing {
    private String unit;

    @JsonDeserialize(using = DoubleStringDeserializer.class)
    private String prompt;

    @JsonDeserialize(using = DoubleStringDeserializer.class)
    private String completion;

    // Generated OpenAPI schema documents this as PricingRate's own object shape only; the
    // generator has no field-level oneOf hook, so the flat-rate-string alternative doesn't
    // render here even though the deserializer accepts it (PricingRateDeserializer).
    private PricingRate cacheRead;

    private PricingRate cacheWrite;
}