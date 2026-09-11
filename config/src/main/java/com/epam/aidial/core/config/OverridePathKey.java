package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.annotation.Nullable;
import lombok.Getter;

/**
 * Core API operations whose upstream path an {@code interfaces.<type>.overridePaths} entry may
 * replace. The string {@link #value} is the key used in {@link DeploymentInterface#getOverridePaths()}.
 */
@Getter
public enum OverridePathKey {

    @JsonAlias({"post_azure_openai_chat_completions"})
    POST_AZURE_OPENAI_CHAT_COMPLETIONS("postAzureOpenaiChatCompletions", InterfaceType.OPENAI_CHAT_COMPLETIONS, true),
    @JsonAlias({"post_azure_openai_embeddings"})
    POST_AZURE_OPENAI_EMBEDDINGS("postAzureOpenaiEmbeddings", InterfaceType.OPENAI_EMBEDDINGS, true),
    @JsonAlias({"post_openai_responses"})
    POST_OPENAI_RESPONSES("postOpenaiResponses", InterfaceType.OPENAI_RESPONSES, false),
    @JsonAlias({"get_openai_responses_by_id"})
    GET_OPENAI_RESPONSES_BY_ID("getOpenaiResponsesById", InterfaceType.OPENAI_RESPONSES, true),
    @JsonAlias({"delete_openai_responses_by_id"})
    DELETE_OPENAI_RESPONSES_BY_ID("deleteOpenaiResponsesById", InterfaceType.OPENAI_RESPONSES, true),
    @JsonAlias({"post_openai_responses_cancel"})
    POST_OPENAI_RESPONSES_CANCEL("postOpenaiResponsesCancel", InterfaceType.OPENAI_RESPONSES, true),
    @JsonAlias({"post_anthropic_messages"})
    POST_ANTHROPIC_MESSAGES("postAnthropicMessages", InterfaceType.ANTHROPIC_MESSAGES, false),
    @JsonAlias({"post_anthropic_messages_count_tokens"})
    POST_ANTHROPIC_MESSAGES_COUNT_TOKENS("postAnthropicMessagesCountTokens", InterfaceType.ANTHROPIC_MESSAGES, false);

    @JsonValue
    private final String value;

    /** The interface type whose {@code overridePaths} map this key is read from. */
    private final InterfaceType interfaceType;

    /** Whether the {@code {id}} template variable applies: the operation addresses an id to render. */
    private final boolean idApplicable;

    OverridePathKey(String value, InterfaceType interfaceType, boolean idApplicable) {
        this.value = value;
        this.interfaceType = interfaceType;
        this.idApplicable = idApplicable;
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
