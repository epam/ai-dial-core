package com.epam.aidial.core.server.data;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class AnthropicErrorData {

    @JsonProperty("type")
    private String type;

    @JsonProperty("error")
    private Detail error;

    @Data
    public static class Detail {
        @JsonProperty("type")
        private String type;

        @JsonProperty("message")
        private String message;
    }
}
