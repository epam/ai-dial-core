package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

import java.util.List;

/**
 * Extensible set of LLM API interface types a deployment may expose.
 * The string {@link #value} is the key used in {@link Deployment#getInterfaces()}.
 */
@Getter
public enum InterfaceType {

    @JsonAlias({"openai_chat_completions"})
    OPENAI_CHAT_COMPLETIONS("openaiChatCompletions", List.of("prefix.body.tools", "prefix.body.messages")),
    @JsonAlias({"openai_responses"})
    OPENAI_RESPONSES("openaiResponses", List.of("prefix.body.tools", "prefix.body.instructions", "prefix.body.input")),
    @JsonAlias({"anthropic_messages"})
    ANTHROPIC_MESSAGES("anthropicMessages", List.of("prefix.body.tools", "prefix.body.system", "prefix.body.messages"));

    @JsonValue
    private final String value;

    /**
     * The node order used to build upstream cache keys, fixed by the wire format of this interface.
     * Only {@link #OPENAI_CHAT_COMPLETIONS} can be overridden by {@code Model#fieldsHashingOrder}; see
     * {@link Model#resolveFieldsHashingOrder(InterfaceType)}.
     */
    private final List<String> defaultFieldsHashingOrder;

    InterfaceType(String value, List<String> defaultFieldsHashingOrder) {
        this.value = value;
        this.defaultFieldsHashingOrder = defaultFieldsHashingOrder;
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
