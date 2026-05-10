package com.epam.aidial.cli.template;

/**
 * Unchecked exception for any template-resolution failure (missing variable, unknown function,
 * malformed expression, cyclic extends, etc.). Callers map it to CLI exit code 2.
 */
public class TemplateException extends RuntimeException {

    public TemplateException(String message) {
        super(message);
    }

    public TemplateException(String message, Throwable cause) {
        super(message, cause);
    }
}
