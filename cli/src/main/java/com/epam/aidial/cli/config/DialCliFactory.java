package com.epam.aidial.cli.config;

import com.epam.aidial.cli.DialCli;
import com.epam.aidial.cli.command.DialExceptionHandler;
import picocli.CommandLine;

public final class DialCliFactory {

    private DialCliFactory() {
    }

    public static CommandLine build() {
        CommandLine cmd = new CommandLine(new DialCli());
        configure(cmd);
        return cmd;
    }

    public static void configure(CommandLine cmd) {
        cmd.setExecutionExceptionHandler(new DialExceptionHandler());
    }
}
