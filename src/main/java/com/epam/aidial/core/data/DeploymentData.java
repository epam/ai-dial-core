package com.epam.aidial.core.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class DeploymentData {
    private String id;
    private String model;
    private String addon;
    private String assistant;
    private String application;
    private String displayName;
    private String iconUrl;
    private String description;
    private String owner = "organization-owner";
    private String object = "deployment";
    private String status = "succeeded";
    private long createdAt = 1672534800;
    private long updatedAt = 1672534800;
    private ScaleSettingsData scaleSettings = new ScaleSettingsData();
    private FeaturesData features;
    private List<String> inputAttachmentTypes;
    private Integer maxInputAttachments;
}
