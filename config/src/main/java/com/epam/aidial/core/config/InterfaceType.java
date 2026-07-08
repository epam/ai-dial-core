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
    OPENAI_RESPONSES("openaiResponses"),
    @JsonAlias({"anthropic_messages"})
    ANTHROPIC_MESSAGES("anthropicMessages");

    @JsonValue
    private final String value;

    InterfaceType(String value) {
        this.value = value;
    }

    /**
     * Resolves an interface type from its string value.
     *
     * @throws IllegalArgumentException if the value does not match any known interface type.
     */
    public static InterfaceType fromValue(String value) {
        for (InterfaceType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown interface type: " + value);
    }
}
