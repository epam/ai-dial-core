package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
import java.util.Set;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GlobalSettings {
    private List<String> globalInterceptors = List.of();
    private Set<Integer> retriableErrorCodes = Set.of();
}
