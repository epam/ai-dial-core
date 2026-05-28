package com.epam.aidial.cli;

import io.quarkus.picocli.runtime.DefaultPicocliCommandLineFactory;
import io.quarkus.picocli.runtime.PicocliConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;

@ApplicationScoped
public class DialCliCommandLineFactory extends DefaultPicocliCommandLineFactory {

    @Inject
    public DialCliCommandLineFactory(Instance<Object> instance, PicocliConfiguration configuration, IFactory factory) {
        super(instance, configuration, factory);
    }

    @Override
    public CommandLine create() {
        return super.create().setExecutionExceptionHandler(new DialExceptionHandler());
    }
}
