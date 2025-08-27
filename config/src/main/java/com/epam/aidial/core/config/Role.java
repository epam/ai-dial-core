package com.epam.aidial.core.config;

import lombok.Data;

import java.util.Map;

@Data
public class Role {
    private String name;
    private Map<String, Limit> limits;
    private CostLimit costLimit;
    private Map<String, ShareResourceLimit> share;
}
