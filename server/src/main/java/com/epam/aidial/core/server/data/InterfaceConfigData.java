package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.server.util.DeploymentEndpointUtil;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One {@code interface_configs} entry of a deployment listing: the features, the defaults and the default
 * headers in force for a single interface the deployment serves.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class InterfaceConfigData {

    private FeaturesData features;
    private Map<String, Object> defaults;
    /**
     * The default headers in force for the interface, resolved by {@link Deployment#resolveDefaultHeaders}.
     * Empty when the deployment defaults no header for this interface, like {@link #defaults}.
     */
    private Map<String, String> defaultHeaders;

    /**
     * The listing's {@code interface_configs} map: one entry per interface the deployment advertises,
     * keyed by the interface's own name. Features, defaults and default headers are the ones in force for
     * that interface; the {@code chat_completion} and {@code responses_api} flags name the API the interface
     * itself is, unlike the deployment-level features where they name the APIs the deployment serves at all.
     */
    @JsonIgnore
    public static Map<String, InterfaceConfigData> createInterfaceConfigs(Deployment deployment) {
        Map<String, InterfaceConfigData> configsByInterface = new LinkedHashMap<>();
        for (InterfaceType type : InterfaceType.values()) {
            if (!DeploymentEndpointUtil.isInterfaceDeclared(deployment, type)) {
                continue;
            }
            InterfaceConfigData config = new InterfaceConfigData();
            FeaturesData features = FeaturesData.createFeatures(deployment.resolveFeatures(type));
            features.setChatCompletion(type == InterfaceType.OPENAI_CHAT_COMPLETIONS);
            features.setResponsesApi(type == InterfaceType.OPENAI_RESPONSES);
            config.setFeatures(features);
            config.setDefaults(deployment.resolveDefaults(type));
            config.setDefaultHeaders(deployment.resolveDefaultHeaders(type));
            configsByInterface.put(type.getValue(), config);
        }
        return configsByInterface;
    }
}
