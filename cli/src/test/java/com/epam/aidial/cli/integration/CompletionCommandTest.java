package com.epam.aidial.cli.integration;

import com.epam.aidial.cli.config.DialCliFactory;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionCommandTest {

    private record Result(int exitCode, String out, String err) { }

    private static Result run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cli = DialCliFactory.build();
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        return new Result(cli.execute(args), out.toString(), err.toString());
    }

    @Test
    void bashEmitsCompletionScript() {
        Result r = run("completion", "bash");

        assertEquals(0, r.exitCode, r.err);
        assertTrue(r.out.contains("dial-cli"), "expected dial-cli in script, got: " + r.out.substring(0, Math.min(200, r.out.length())));
        assertTrue(r.out.contains("complete") || r.out.contains("_complete"),
                "expected bash completion directives, got: " + r.out.substring(0, Math.min(200, r.out.length())));
    }

    @Test
    void zshEmitsSameAsBash() {
        Result bash = run("completion", "bash");
        Result zsh = run("completion", "zsh");

        assertEquals(0, zsh.exitCode, zsh.err);
        assertEquals(bash.out, zsh.out);
    }

    @Test
    void scriptReferencesAllTopLevelSubcommands() {
        Result r = run("completion", "bash");

        assertEquals(0, r.exitCode);
        for (String name : new String[]{"env", "get", "model", "application", "toolset",
                "interceptor", "role", "key", "route", "schema", "settings",
                "completion"}) {
            assertTrue(r.out.contains(name), "completion script missing subcommand `" + name + "`");
        }
    }

    @Test
    void fishExitsTwoWithMessage() {
        Result r = run("completion", "fish");

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("fish"), r.err);
        assertTrue(r.err.contains("not yet supported"), r.err);
    }

    @Test
    void unknownShellExitsTwo() {
        Result r = run("completion", "powershell");

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("Unknown shell"), r.err);
        assertTrue(r.err.contains("powershell"), r.err);
    }

    @Test
    void missingShellArgExitsTwo() {
        Result r = run("completion");

        assertEquals(2, r.exitCode);
        assertTrue(r.err.contains("Missing required parameter"), r.err);
    }
}
