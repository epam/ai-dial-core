package com.epam.aidial.core.server.data.codeinterpreter;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CodeInterpreterInputFile {
    String sessionId;
    String sourceUrl;
    String targetPath;
}