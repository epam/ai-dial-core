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
    void exposesAllPerTypeReadCommands() {
        CommandLine cmd = new CommandLine(new DialCli());
        Set<String> subcommands = cmd.getSubcommands().keySet();

        Set<String> expected = Set.of(
                "model", "application", "toolset",
                "interceptor", "role", "key", "route", "schema", "settings");
        assertTrue(subcommands.containsAll(expected),
                "expected " + expected + ", got " + subcommands);
    }

    @Test
    void perTypeCommandsExposeGetAndList() {
        CommandLine root = new CommandLine(new DialCli());
        for (String type : new String[]{"model", "application", "toolset",
                "interceptor", "role", "key", "route", "schema"}) {
            CommandLine typeCmd = root.getSubcommands().get(type);
            Set<String> children = typeCmd.getSubcommands().keySet();
            assertTrue(children.contains("get"), type + " missing `get`, has " + children);
            assertTrue(children.contains("list"), type + " missing `list`, has " + children);
        }
    }

    @Test
    void settingsExposesGetOnly() {
        CommandLine root = new CommandLine(new DialCli());
        Set<String> children = root.getSubcommands().get("settings").getSubcommands().keySet();

        assertTrue(children.contains("get"), "settings missing `get`, has " + children);
        org.junit.jupiter.api.Assertions.assertFalse(
                children.contains("list"), "settings should NOT expose `list` (singleton), has " + children);
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
