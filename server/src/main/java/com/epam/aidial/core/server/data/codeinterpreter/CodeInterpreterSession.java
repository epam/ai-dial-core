package com.epam.aidial.core.server.data.codeinterpreter;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CodeInterpreterSession {
    String sessionId;
    String deploymentId;
    String deploymentUrl;
    DeploymentType deploymentType = DeploymentType.DEPLOYMENT;
    Long usedAt;

    public enum DeploymentType {
        SESSION, DEPLOYMENT;
    }
}