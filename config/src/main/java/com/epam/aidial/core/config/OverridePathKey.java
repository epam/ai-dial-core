package com.epam.aidial.core.config;

import jakarta.annotation.Nullable;
import lombok.Getter;

/**
 * Core API operations whose upstream path an {@code interfaces.<type>.overridePaths} entry may
 * replace. The string {@link #value} is the key used in {@link DeploymentInterface#getOverridePaths()};
 * {@link #alias} is its snake_case spelling, accepted the same way {@link InterfaceType} accepts its
 * snake_case aliases. That map deserializes with plain string keys, so the alias is resolved here —
 * by {@link #find} and the map lookup — rather than by a Jackson annotation.
 */
@Getter
public enum OverridePathKey {

    POST_AZURE_OPENAI_CHAT_COMPLETIONS("postAzureOpenaiChatCompletions", "post_azure_openai_chat_completions",
            InterfaceType.OPENAI_CHAT_COMPLETIONS, true),
    POST_AZURE_OPENAI_EMBEDDINGS("postAzureOpenaiEmbeddings", "post_azure_openai_embeddings",
            InterfaceType.OPENAI_EMBEDDINGS, true),
    POST_OPENAI_RESPONSES("postOpenaiResponses", "post_openai_responses",
            InterfaceType.OPENAI_RESPONSES, false),
    GET_OPENAI_RESPONSES_BY_ID("getOpenaiResponsesById", "get_openai_responses_by_id",
            InterfaceType.OPENAI_RESPONSES, true),
    DELETE_OPENAI_RESPONSES_BY_ID("deleteOpenaiResponsesById", "delete_openai_responses_by_id",
            InterfaceType.OPENAI_RESPONSES, true),
    POST_OPENAI_RESPONSES_CANCEL("postOpenaiResponsesCancel", "post_openai_responses_cancel",
            InterfaceType.OPENAI_RESPONSES, true),
    POST_ANTHROPIC_MESSAGES("postAnthropicMessages", "post_anthropic_messages",
            InterfaceType.ANTHROPIC_MESSAGES, false),
    POST_ANTHROPIC_MESSAGES_COUNT_TOKENS("postAnthropicMessagesCountTokens", "post_anthropic_messages_count_tokens",
            InterfaceType.ANTHROPIC_MESSAGES, false);

    private final String value;

    /** The snake_case spelling of {@link #value}. The camelCase value wins when a map declares both. */
    private final String alias;

    /** The interface type whose {@code overridePaths} map this key is read from. */
    private final InterfaceType interfaceType;

    /** Whether the operation carries an id the {@code {id}} template variable can render. */
    private final boolean idAvailable;

    OverridePathKey(String value, String alias, InterfaceType interfaceType, boolean idAvailable) {
        this.value = value;
        this.alias = alias;
        this.interfaceType = interfaceType;
        this.idAvailable = idAvailable;
    }

    /**
     * The key with this value or alias, or null for one this Core does not know — a config may name
     * a key that only a newer Core understands.
     */
    @Nullable
    public static OverridePathKey find(String value) {
        for (OverridePathKey key : values()) {
            if (key.value.equals(value) || key.alias.equals(value)) {
                return key;
            }
        }
        return null;
    }
}
