package com.epam.aidial.cli.service.auth;

import java.io.Console;

@FunctionalInterface
public interface PasswordPrompter {

    /**
     * @return entered secret, or {@code null} when no TTY/prompter is available.
     */
    String prompt(String message);

    PasswordPrompter SYSTEM = message -> {
        Console console = System.console();
        if (console == null) {
            return null;
        }
        char[] input = console.readPassword(message);
        return (input == null) ? null : new String(input);
    };
}
