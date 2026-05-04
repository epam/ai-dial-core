package com.epam.aidial.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "get", description = "Read entities (kubectl-style alias for <type> list).", mixinStandardHelpOptions = true)
public class GetCommand implements Runnable {

    @Parameters(arity = "0..1", description = "Resource type (e.g. models, roles, keys).")
    String resourceType;

    @Override
    public void run() {
        throw new UnsupportedOperationException("get — wires up in slice 1C.2 / 1C.3");
    }
}
