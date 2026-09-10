package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.annotation.Nullable;
import lombok.Getter;

import java.util.List;

/**
 * Extensible set of LLM API interface types a deployment may expose.
 * The string {@link #value} is the key used in {@link Deployment#getInterfaces()}.
 */
@Getter
public enum InterfaceType {

    @JsonAlias({"openai_chat_completions"})
    OPENAI_CHAT_COMPLETIONS(
            "openaiChatCompletions",
            "/v1/chat/completions",
            List.of("prefix.body.tools", "prefix.body.messages")
    ),
    // an embeddings request carries no prompt prefix to cache, so it contributes no cache keys
    @JsonAlias({"openai_embeddings"})
    OPENAI_EMBEDDINGS(
            "openaiEmbeddings",
            "/v1/embeddings",
            List.of()
    ),
    @JsonAlias({"openai_responses"})
    OPENAI_RESPONSES(
            "openaiResponses",
            "/v1/responses",
            List.of("prefix.body.tools", "prefix.body.instructions", "prefix.body.input")
    ),
    @JsonAlias({"anthropic_messages"})
    ANTHROPIC_MESSAGES(
            "anthropicMessages",
            "/v1/messages",
            List.of("prefix.body.tools", "prefix.body.system", "prefix.body.messages")
    );

    @JsonValue
    private final String value;

    /**
     * The path this interface's own API spec serves it at, with no DIAL ingress keyword
     * ({@code /openai}, {@code /anthropic}) in front of it. Appended to {@code Upstream#baseUrl}
     * to address a provider that hosts the API where its spec says it should be.
     */
    private final String apiPath;

    /**
     * The node order used to build upstream cache keys, hardcoded per the wire format of this
     * interface. {@code Model#fieldsHashingOrder} is deprecated and no longer overrides this, even
     * for {@link #OPENAI_CHAT_COMPLETIONS}.
     */
    private final List<String> fieldsHashingOrder;

    InterfaceType(String value, String apiPath, List<String> fieldsHashingOrder) {
        this.value = value;
        this.apiPath = apiPath;
        this.fieldsHashingOrder = fieldsHashingOrder;
    }

    /**
     * Resolves an interface type from its string value.
     *
     * @throws IllegalArgumentException if the value does not match any known interface type.
     */
    public static InterfaceType fromValue(String value) {
        InterfaceType type = find(value);
        if (type == null) {
            throw new IllegalArgumentException("Unknown interface type: " + value);
        }
        return type;
    }

    /**
     * The type with this value, or null for one this Core does not know — a config may name an interface
     * that only a newer Core, or another component, understands.
     */
    @Nullable
    public static InterfaceType find(String value) {
        for (InterfaceType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        return null;
    }
}
