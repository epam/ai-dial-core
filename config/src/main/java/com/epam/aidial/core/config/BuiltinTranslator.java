package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * A translation Core performs in process, inside the request it is serving, instead of handing the request
 * to a translator service that calls Core back. The set is fixed by the code, so a {@link Translator#getBuiltin()}
 * value Core does not know is rejected as the config is read.
 */
@Getter
public enum BuiltinTranslator {

    ANTHROPIC_MESSAGES_TO_OPENAI_RESPONSES("anthropicMessagesToOpenaiResponses",
            InterfaceType.ANTHROPIC_MESSAGES, InterfaceType.OPENAI_RESPONSES);

    @JsonValue
    private final String value;

    private final InterfaceType in;

    private final InterfaceType out;

    BuiltinTranslator(String value, InterfaceType in, InterfaceType out) {
        this.value = value;
        this.in = in;
        this.out = out;
    }
}
