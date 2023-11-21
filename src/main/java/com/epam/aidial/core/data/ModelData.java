package com.epam.aidial.core.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ModelData extends DeploymentData {

    private String lifecycleStatus = "generally-available";
    private CapabilitiesData capabilities = new CapabilitiesData();
    private TokenLimitsData limits = new TokenLimitsData();
    private TokenizerData tokenizer = new TokenizerData();

    {
        setObject("model");
        setScaleSettings(null);
    }
}