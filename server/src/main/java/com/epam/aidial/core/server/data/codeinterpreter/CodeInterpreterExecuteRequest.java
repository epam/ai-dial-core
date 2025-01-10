package com.epam.aidial.core.server.data.codeinterpreter;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CodeInterpreterExecuteRequest {
    private String sessionId;
    private String code;
    private List<CodeInterpreterInputFile> inputFiles;
    private List<CodeInterpreterOutputFile> outputFiles;
}