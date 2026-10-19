package com.epam.aidial.core.config;

import lombok.Data;

@Data
public class Pricing {
    private String unit;

    // Generated OpenAPI schema documents these as PricingRate's own object shape only; the
    // generator has no field-level oneOf hook, so the flat-rate-string alternative doesn't
    // render here even though the deserializer accepts it (PricingRateDeserializer).
    private PricingRate prompt;

    private PricingRate completion;

    private PricingRate cacheRead;

    private PricingRate cacheWrite;
}