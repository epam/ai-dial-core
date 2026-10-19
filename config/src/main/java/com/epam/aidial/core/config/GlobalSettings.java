package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Set;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GlobalSettings {
    private List<@NotNull String> globalInterceptors = List.of();
    private Set<@NotNull Integer> retriableErrorCodes = Set.of();
    @Valid
    private RateLimitSchedule rateLimitSchedule = new RateLimitSchedule();
}
