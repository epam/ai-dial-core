package com.epam.aidial.core.config;

import lombok.Data;

@Data
public class Pricing {
    private String unit;
    private Double prompt;
    private Double completion;
}