package com.epam.aidial.core.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class FeaturesData {
    private boolean rate = false;
    private boolean tokenize = false;
    private boolean truncatePrompt = false;
    private boolean configuration = false;

    private boolean systemPrompt = true;
    private boolean tools = false;
    private boolean seed = false;
    private boolean urlAttachments = false;
    private boolean folderAttachments = false;
}