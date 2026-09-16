package com.epam.aidial.core.server.data.anthropic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AnthropicModelData {
    private String id;
    private String type = "model";
    private String displayName;
    private String createdAt;
    private Integer maxInputTokens;
    private Integer maxTokens;
}
