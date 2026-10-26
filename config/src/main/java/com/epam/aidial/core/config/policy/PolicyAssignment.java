package com.epam.aidial.core.config.policy;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * One grant of a {@link Policy}: who it reaches and in which cost object. Pins whichever of
 * {@code subject} and {@code costObject} the policy leaves open.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PolicyAssignment {
    /**
     * Path of the assignment under {@code policy_assignments}; when set it must match the path of the request.
     */
    private String id;
    /**
     * Path of the policy under {@code policies}.
     */
    private String policy;
    private List<PolicySubjectRule> subject;
    private String costObject;
    private String source;
    private String ticket;
    /**
     * ISO-8601 date-time with an offset.
     */
    private String expiresAt;
    private String description;
}
