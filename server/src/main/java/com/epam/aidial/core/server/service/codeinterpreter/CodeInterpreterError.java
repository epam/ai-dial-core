package com.epam.aidial.core.server.service.codeinterpreter;

import lombok.Getter;

@Getter
public class CodeInterpreterError extends RuntimeException {
    private final int status;

    public CodeInterpreterError(int status, String body) {
        super(body);
        this.status = status;
    }
}