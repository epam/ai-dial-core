package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum PricingUnit {
    @JsonProperty("token") TOKEN,
    @JsonProperty("char_without_whitespace") CHAR_WITHOUT_WHITESPACE;
}
