package com.epam.aidial.core.config;

import com.epam.aidial.core.util.JsonToStringDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Upstream {

    public static final int ERROR_THRESHOLD = 3;

    private String endpoint;
    private String key;
    @JsonDeserialize(using = JsonToStringDeserializer.class)
    private String extraData;
    private int weight = 1;
    private int tier = 0;
}