package com.epam.deltix.dial.proxy.config;

import lombok.Data;

import java.util.Map;

@Data
public class Assistants {
    private String endpoint;
    private Map<String, Assistant> assistants = Map.of();
}