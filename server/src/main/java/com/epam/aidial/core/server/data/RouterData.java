package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.LocalizedValue;
import com.epam.aidial.core.config.Router;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * A router as {@code GET /v1/routers} lists it: display and feature fields, never the url it is served at.
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
        if (router.getCreatedAt() != null) {
            data.setCreatedAt(router.getCreatedAt());
        }
        if (router.getUpdatedAt() != null) {
            data.setUpdatedAt(router.getUpdatedAt());
        }
        return data;
    }
}
