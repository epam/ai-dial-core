package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.ToolSet;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ToolSetData extends SecuredResourceData {

    private String transport;
    private List<String> allowedTools;
    private String provider;
    private String vendorWebsite;

    {
        setObject("toolset");
        setScaleSettings(null);
    }

    @JsonIgnore
    public static ToolSetData toData(ToolSet toolSet) {

        ToolSetData data = new ToolSetData();

        data.setToolset(toolSet.getName());
        data.setId(toolSet.getName());
        if (toolSet.getDisplayName() != null) {
            data.setDisplayName(toolSet.getDisplayName());
        } else {
            data.setDisplayName(toolSet.getName());
        }
        data.setIconUrl(toolSet.getIconUrl());
        data.setDisplayVersion(toolSet.getDisplayVersion());
        data.setDescription(toolSet.getDescription());
        data.setIntro(toolSet.getIntro());
        data.setDescriptionKeywords(toolSet.getDescriptionKeywords());
        data.setReference(toolSet.getReference() == null ? toolSet.getName() : toolSet.getReference());
        FeaturesData featuresData = FeaturesData.createFeatures(toolSet.getFeatures());
        featuresData.setMcp(true);
        data.setFeatures(featuresData);

        data.setMaxRetryAttempts(toolSet.getMaxRetryAttempts());

        if (toolSet.getAuthor() != null) {
            data.setOwner(toolSet.getAuthor());
        }
        if (toolSet.getCreatedAt() != null) {
            data.setCreatedAt(toolSet.getCreatedAt());
        }
        if (toolSet.getUpdatedAt() != null) {
            data.setUpdatedAt(toolSet.getUpdatedAt());
        }

        data.setAllowedTools(toolSet.getAllowedTools());
        data.setTransport(toolSet.getTransport().toString());
        data.setAuthSettings(ResourceAuthSettingsData.toData(toolSet.getAuthSettings()));
        data.setProvider(toolSet.getProvider());
        data.setVendorWebsite(toolSet.getVendorWebsite());

        return data;
    }
}
