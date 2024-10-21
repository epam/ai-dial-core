package com.epam.aidial.core.config;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class Assistants {
    private String endpoint;
    private Features features;
    private Map<String, Assistant> assistants = new HashMap<>();
}