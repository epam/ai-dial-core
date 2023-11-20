package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Config {
    // maintain the order of routes defined in the config
    private LinkedHashMap<String, Route> routes = new LinkedHashMap<>();
    private Map<String, Model> models = Map.of();
    private Map<String, Addon> addons = Map.of();
    private Map<String, Application> applications = Map.of();
    private Assistants assistant = new Assistants();
    private Map<String, Key> keys = Map.of();
    private Map<String, Role> roles = Map.of();
}
