package com.epam.aidial.core.config;

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
    private int weight = 1;
    private int tier = 0;
}