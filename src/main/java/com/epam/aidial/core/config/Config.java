package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Config {
    public static final String ASSISTANT = "assistant";

    // maintain the order of routes defined in the config
    private LinkedHashMap<String, Route> routes = new LinkedHashMap<>();
    private Map<String, Model> models = Map.of();
    private Map<String, Addon> addons = Map.of();
    private Map<String, Application> applications = Map.of();
    private Assistants assistant = new Assistants();
    private Map<String, Key> keys = Map.of();
    private Map<String, Role> roles = Map.of();
    private Set<String> deploymentApiKeys = new HashSet<>();


    public Deployment selectDeployment(String deploymentId) {
        Application application = applications.get(deploymentId);
        if (application != null) {
            return application;
        }

        Model model = models.get(deploymentId);
        if (model != null) {
            return model;
        }

        Assistants assistants = assistant;
        Assistant assistant = assistants.getAssistants().get(deploymentId);
        if (assistant != null) {
            return assistant;
        }

        if (assistants.getEndpoint() != null && ASSISTANT.equals(deploymentId)) {
            Assistant baseAssistant = new Assistant();
            baseAssistant.setName(ASSISTANT);
            baseAssistant.setEndpoint(assistants.getEndpoint());
            baseAssistant.setFeatures(assistants.getFeatures());
            return baseAssistant;
        }

        return null;
    }

    public boolean existDeploymentApiKey(String apiKey) {
        return deploymentApiKeys.contains(apiKey);
    }
}
