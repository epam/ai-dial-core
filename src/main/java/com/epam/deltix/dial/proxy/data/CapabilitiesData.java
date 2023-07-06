package com.epam.deltix.dial.proxy.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class CapabilitiesData {
    private String[] scaleTypes = {"standard"};
    private boolean completion;
    private boolean embeddings;
    private boolean fineTune;
    private boolean inference;
}