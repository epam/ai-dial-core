package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * Extensible set of LLM API interface types a deployment may expose.
 * The string {@link #value} is the key used in {@link Deployment#getInterfaces()}.
 */
@Getter
public enum InterfaceType {

    @JsonAlias({"openai_chat_completions"})
    OPENAI_CHAT_COMPLETIONS("openaiChatCompletions"),
    @JsonAlias({"openai_responses"})
    OPENAI_RESPONSES("openaiResponses");

    @JsonValue
    private final String value;

    InterfaceType(String value) {
        this.value = value;
    }

    /**
     * Resolves an interface type from its string value, or {@code null} when the value is
     * unknown/null. Unknown keys are tolerated (treated as "interface not supported").
     */
    public static InterfaceType fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (InterfaceType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        return null;
    }
}
