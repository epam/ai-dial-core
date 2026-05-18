package com.epam.aidial.cli;

import picocli.AutoComplete;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

@Command(
        name = "completion",
        description = "Generate a shell completion script (bash | zsh | fish).",
        mixinStandardHelpOptions = true
)
public class CompletionCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Parameters(index = "0", description = "Shell: bash, zsh, or fish.")
    String shell;

    @Override
    public Integer call() {
        return switch (shell) {
            case "bash", "zsh" -> {
                CommandLine root = spec.root().commandLine();
                String script = AutoComplete.bash("dial-cli", root);
                spec.commandLine().getOut().print(script);
                yield 0;
            }
            case "fish" -> {
                spec.commandLine().getErr().println(
                        "fish completion is not yet supported by Picocli; use bash or zsh.");
                yield 2;
            }
            default -> {
                spec.commandLine().getErr().println(
                        "Unknown shell: " + shell + " (expected bash, zsh, or fish).");
                yield 2;
            }
        };
    }
}
