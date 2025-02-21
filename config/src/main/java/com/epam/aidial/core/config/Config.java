package com.epam.aidial.core.config;

import com.epam.aidial.core.config.databind.JsonArrayToSchemaMapDeserializer;
import com.epam.aidial.core.config.databind.MapToJsonArraySerializer;
import com.epam.aidial.core.config.validation.ConformToMetaSchema;
import com.epam.aidial.core.config.validation.CustomApplicationsConformToTypeSchemas;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Data;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@CustomApplicationsConformToTypeSchemas(message = "All custom schema-rich applications should conform to their schemas")
public class Config {
    public static final String ASSISTANT = "assistant";

    // maintain the order of routes defined in the config
    private LinkedHashMap<String, Route> routes = new LinkedHashMap<>();
    private Map<String, Model> models = Map.of();
    private Map<String, Addon> addons = Map.of();
    private Map<String, Application> applications = Map.of();
    private Assistants assistant = new Assistants();
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private Map<String, Key> keys = new HashMap<>();
    private Map<String, Role> roles = new HashMap<>();
    private Set<Integer> retriableErrorCodes = Set.of();
    private Map<String, Interceptor> interceptors = Map.of();

    @JsonDeserialize(using = JsonArrayToSchemaMapDeserializer.class)
    @JsonSerialize(using = MapToJsonArraySerializer.class)
    @ConformToMetaSchema(message = "All custom application type schemas should conform to meta schema")
    private Map<String, String> applicationTypeSchemas = Map.of();


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
        return assistants.getAssistants().get(deploymentId);
    }


    public String getCustomApplicationSchema(URI schemaId) {
        if (schemaId == null) {
            return null;
        }
        return applicationTypeSchemas.get(schemaId.toString());
    }
}
