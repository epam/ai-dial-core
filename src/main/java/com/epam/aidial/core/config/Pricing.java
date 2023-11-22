package com.epam.aidial.core.config;

import lombok.Data;

@Data
public class Pricing {
    private String unit;
    private String prompt;
    private String completion;
}