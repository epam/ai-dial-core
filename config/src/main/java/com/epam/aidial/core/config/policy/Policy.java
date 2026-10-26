package com.epam.aidial.core.config.policy;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.List;

/**
 * What a budget covers and how much it allows. {@code subject} and {@code costObject} are pinned here
 * or by each {@link PolicyAssignment} that references the policy, never by both: a policy pinning both
 * applies by itself.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Policy {
    /**
     * Path of the policy under {@code policies}; when set it must match the path of the request.
     */
    private String id;
    private Integer priority;
    private PolicyKind kind;
    private PolicyQuantity quantity;
    private PolicyObjectSet object;
    private List<String> per;
    private PolicyAmounts cost;
    private List<PolicySubjectRule> subject;
    private String costObject;

    // Reserved for later releases: parsed, then rejected by the validator.
    private JsonNode attachedTo;
    private JsonNode harness;
    private JsonNode broker;
    private JsonNode packages;

    @JsonIgnore
    public boolean isSelfApplying() {
        return subject != null && costObject != null;
    }
}
