package com.epam.aidial.core.config;

import com.epam.aidial.core.config.policy.PolicySubjectRule;
import com.epam.aidial.core.config.validation.ValidSubject;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * What the analytics log writes for whom. {@code securedSubject}: the callers whose rows go without request and
 * response bodies, as a secured key's do; its rules are OR-composed, as a policy assignment's subject is, and an
 * empty list matches nobody.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnalyticsConfig {
    @NotNull
    @ValidSubject
    private List<PolicySubjectRule> securedSubject = List.of();
}
