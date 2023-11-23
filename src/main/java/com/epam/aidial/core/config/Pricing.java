package com.epam.aidial.core.config;

import com.epam.aidial.core.util.DoubleStringDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;

@Data
public class Pricing {
    private String unit;

    @JsonDeserialize(using = DoubleStringDeserializer.class)
    private String prompt;

    @JsonDeserialize(using = DoubleStringDeserializer.class)
    private String completion;
}