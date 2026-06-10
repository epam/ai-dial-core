package com.epam.aidial.cli.command;

import com.epam.aidial.cli.exception.CliException;
import picocli.CommandLine;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.ParseResult;

public class DialExceptionHandler implements IExecutionExceptionHandler {

    @Override
    public int handleExecutionException(Exception ex, CommandLine cmd, ParseResult parseResult) throws Exception {
        if (ex instanceof CliException ce) {
            cmd.getErr().println(ce.getMessage());
            return ce.exitCode();
        }
        throw ex;
    }
}
