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
public class ToolSetData extends DeploymentData {
    private String transport;
    private List<String> allowedTools;

    {
        setObject("toolset");
        setScaleSettings(null);
    }

    @JsonIgnore
    public static ToolSetData toData(ToolSet toolSet) {
        ToolSetData data = new ToolSetData();

        data.setToolset(toolSet.getName());
        data.setId(toolSet.getName());
        data.setDisplayName(toolSet.getDisplayName());
        data.setIconUrl(toolSet.getIconUrl());
        data.setDisplayVersion(toolSet.getDisplayVersion());
        data.setDescription(toolSet.getDescription());
        data.setDescriptionKeywords(toolSet.getDescriptionKeywords());
        data.setReference(toolSet.getReference());
        data.setFeatures(FeaturesData.createFeatures(toolSet.getFeatures()));

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

        return data;
    }
}
