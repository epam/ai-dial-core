package com.epam.aidial.core.config.policy;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * One rule of a subject: the rules of a subject are OR-composed and evaluated against the claims of the
 * root initiator of a call.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PolicySubjectRule {
    /**
     * {@code roles} or a dotted claim path.
     */
    private String source;
    private Function function;
    private List<String> targets;

    public enum Function {
        TRUE, FALSE, EQUAL, CONTAIN, REGEX
    }
}
