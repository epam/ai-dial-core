package com.epam.aidial.cli;

import io.quarkus.picocli.runtime.DefaultPicocliCommandLineFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import picocli.CommandLine;

@ApplicationScoped
public class CliConfiguration {

    @Inject
    DefaultPicocliCommandLineFactory factory;

    @Produces
    public CommandLine commandLine() {
        CommandLine cmd = factory.create();
        DialCliFactory.configure(cmd);
        return cmd;
    }
}
