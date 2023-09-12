package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Config {
    private Map<String, Route> routes = Map.of();
    private Map<String, Model> models = Map.of();
    private Map<String, Addon> addons = Map.of();
    private Map<String, Application> applications = Map.of();
    private Assistants assistant = new Assistants();
    private Map<String, Key> keys = Map.of();
    private Map<String, Role> roles = Map.of();
}
