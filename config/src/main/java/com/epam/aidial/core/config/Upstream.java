package com.epam.aidial.core.config;

import com.epam.aidial.core.config.databind.JsonToStringDeserializer;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Upstream {

    @JsonAlias({"endpoint", "dial:endpoint"})
    private String endpoint;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonAlias({"responsesEndpoint", "dial:responsesEndpoint"})
    private String responsesEndpoint;
    @ToString.Exclude
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @JsonAlias({"key", "dial:key"})
    private String key;
    @JsonDeserialize(using = JsonToStringDeserializer.class)
    @JsonAlias({"extraData", "dial:extraData"})
    private String extraData;
    @JsonAlias({"weight", "dial:weight"})
    private int weight = 1;
    @JsonAlias({"tier", "dial:tier"})
    private int tier = 0;
}