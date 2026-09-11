package com.epam.aidial.core.config;

import jakarta.annotation.Nullable;
import lombok.Getter;

/**
 * Core API operations whose upstream path an {@code interfaces.<type>.overridePaths} entry may
 * replace. The string {@link #value} is the key used in {@link DeploymentInterface#getOverridePaths()}.
 */
@Getter
public enum OverridePathKey {

    POST_AZURE_OPENAI_CHAT_COMPLETIONS("postAzureOpenaiChatCompletions", InterfaceType.OPENAI_CHAT_COMPLETIONS, true),
    POST_AZURE_OPENAI_EMBEDDINGS("postAzureOpenaiEmbeddings", InterfaceType.OPENAI_EMBEDDINGS, true),
    POST_OPENAI_RESPONSES("postOpenaiResponses", InterfaceType.OPENAI_RESPONSES, false),
    GET_OPENAI_RESPONSES_BY_ID("getOpenaiResponsesById", InterfaceType.OPENAI_RESPONSES, true),
    DELETE_OPENAI_RESPONSES_BY_ID("deleteOpenaiResponsesById", InterfaceType.OPENAI_RESPONSES, true),
    POST_OPENAI_RESPONSES_CANCEL("postOpenaiResponsesCancel", InterfaceType.OPENAI_RESPONSES, true),
    POST_ANTHROPIC_MESSAGES("postAnthropicMessages", InterfaceType.ANTHROPIC_MESSAGES, false),
    POST_ANTHROPIC_MESSAGES_COUNT_TOKENS("postAnthropicMessagesCountTokens", InterfaceType.ANTHROPIC_MESSAGES, false);

    private final String value;

    /** The interface type whose {@code overridePaths} map this key is read from. */
    private final InterfaceType interfaceType;

    /** Whether the operation carries an id the {@code {id}} template variable can render. */
    private final boolean idAvailable;

    OverridePathKey(String value, InterfaceType interfaceType, boolean idAvailable) {
        this.value = value;
        this.interfaceType = interfaceType;
        this.idAvailable = idAvailable;
    }

    /**
     * The key with this value, or null for one this Core does not know — a config may name a key
     * that only a newer Core understands.
     */
    @Nullable
    public static OverridePathKey find(String value) {
        for (OverridePathKey key : values()) {
            if (key.value.equals(value)) {
                return key;
            }
        }
        return null;
    }
}
