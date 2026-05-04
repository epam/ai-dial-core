package com.epam.aidial.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialCliSmokeTest {

    @Test
    void exposesSkeletonSubcommands() {
        CommandLine cmd = new CommandLine(new DialCli());
        Set<String> subcommands = cmd.getSubcommands().keySet();

        assertTrue(subcommands.contains("env"), "expected `env` subcommand, got " + subcommands);
        assertTrue(subcommands.contains("get"), "expected `get` subcommand, got " + subcommands);
    }

    @Test
    void envSubcommandExposesPhase1Children() {
        CommandLine env = new CommandLine(new DialCli()).getSubcommands().get("env");
        Set<String> children = env.getSubcommands().keySet();

        assertTrue(children.containsAll(Set.of("list", "current", "use", "check")),
                "env children must cover Phase-1 1C.1 surface, got " + children);
    }

    @Test
    void helpExitsZero() {
        assertHelpExitsZero();
    }

    @Test
    void envHelpExitsZero() {
        assertHelpExitsZero("env", "--help");
    }

    @Test
    void getHelpExitsZero() {
        assertHelpExitsZero("get", "--help");
    }

    private static void assertHelpExitsZero(String... args) {
        CommandLine cmd = new CommandLine(new DialCli());
        cmd.setOut(new java.io.PrintWriter(java.io.OutputStream.nullOutputStream()));
        cmd.setErr(new java.io.PrintWriter(java.io.OutputStream.nullOutputStream()));
        String[] effective = (args.length == 0) ? new String[]{"--help"} : args;

        int exit = cmd.execute(effective);

        assertEquals(0, exit);
    }
}
