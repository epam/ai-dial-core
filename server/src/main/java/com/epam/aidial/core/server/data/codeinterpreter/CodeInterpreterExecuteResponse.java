package com.epam.aidial.core.server.data.codeinterpreter;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CodeInterpreterExecuteResponse {
    private String status;
    private String stdout;
    private String stderr;
    private Object result;
    private Object display;
}