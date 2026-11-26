package com.epam.aidial.core.config.policy;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * A named set of deployment names that a policy's {@code object} references as {@code @<name>}. Global and flat:
 * stored at {@code packages/<name>}, it holds no other package and no {@code *}.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PolicyPackage {
    private String name;
    private List<String> deployments;
    private String description;
}
