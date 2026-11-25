package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.LocalizedValue;
import com.epam.aidial.core.config.Router;
import com.epam.aidial.core.server.util.DeploymentEndpointUtil;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/**
 * A router as {@code GET /v1/routers} and the generic {@code /v1/deployments} listings show it: display, feature and
 * interface fields, never the url it is served at.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class RouterData extends DeploymentData {

    {
        setObject("router");
        setScaleSettings(null);
    }

    public static RouterData toData(Router router) {
        RouterData data = new RouterData();
        data.setId(router.getName());
        data.setDisplayName(router.getDisplayName() != null ? router.getDisplayName() : LocalizedValue.of(router.getName()));
        data.setDisplayVersion(router.getDisplayVersion());
        data.setIconUrl(router.getIconUrl());
        data.setDescription(router.getDescription());
        data.setIntro(router.getIntro());
        data.setReference(router.getReference() == null ? router.getName() : router.getReference());
        data.setFeatures(FeaturesData.createDeploymentFeatures(router));
        data.setDefaults(router.getDefaults());
        data.setDescriptionKeywords(router.getDescriptionKeywords());
        data.setInterfaces(interfaces(router));
        if (router.getCreatedAt() != null) {
            data.setCreatedAt(router.getCreatedAt());
        }
        if (router.getUpdatedAt() != null) {
            data.setUpdatedAt(router.getUpdatedAt());
        }
        return data;
    }

    // the generic listings' kinds (chat, embedding), then the declared interface types, as for an application
    private static List<String> interfaces(Router router) {
        List<String> interfaces = new ArrayList<>();
        if (DeploymentEndpointUtil.isInterfaceDeclared(router, InterfaceType.OPENAI_CHAT_COMPLETIONS)) {
            interfaces.add("chat");
        }
        if (DeploymentEndpointUtil.isInterfaceDeclared(router, InterfaceType.OPENAI_EMBEDDINGS)) {
            interfaces.add("embedding");
        }
        for (InterfaceType type : InterfaceType.values()) {
            if (DeploymentEndpointUtil.isInterfaceDeclared(router, type)) {
                interfaces.add(type.getValue());
            }
        }
        return interfaces;
    }
}
