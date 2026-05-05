package com.epam.aidial.cli;

import picocli.AutoComplete;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

@Command(
        name = "completion",
        description = "Generate a shell completion script (bash | zsh | fish).",
        mixinStandardHelpOptions = true
)
public class CompletionCommand implements Callable<Integer> {

    @ParentCommand
    DialCli parent;
    @Spec
    CommandSpec spec;

    @Parameters(index = "0", arity = "0..1", description = "Shell: bash, zsh, or fish.")
    String shell;

    @Override
    public Integer call() {
        if (shell == null) {
            spec.commandLine().getErr().println("Specify shell: bash, zsh, or fish.");
            return 2;
        }
        switch (shell) {
            case "bash":
            case "zsh":
                CommandLine root = spec.root().commandLine();
                String script = AutoComplete.bash("dial-cli", root);
                spec.commandLine().getOut().print(script);
                return 0;
            case "fish":
                spec.commandLine().getErr().println(
                        "fish completion is not yet supported by Picocli; use bash or zsh.");
                return 2;
            default:
                spec.commandLine().getErr().println(
                        "Unknown shell: " + shell + " (expected bash, zsh, or fish).");
                return 2;
        }
    }
}
