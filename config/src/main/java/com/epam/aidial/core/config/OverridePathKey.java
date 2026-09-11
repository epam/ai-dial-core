package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.annotation.Nullable;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * Core API operations whose upstream path an {@code interfaces.<type>.overridePaths} entry may
 * replace. The string {@link #value} is the key used in {@link DeploymentInterface#getOverridePaths()},
 * and {@link JsonAlias} declares its accepted snake_case spelling, exactly as {@link InterfaceType}
 * does. That map deserializes with plain string keys Jackson never binds to this enum, so the
 * annotation is consulted by {@link #find} and {@link #findTemplate} rather than by Jackson itself.
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

    /** Every accepted spelling to its key: the camelCase value and the {@link JsonAlias} spellings. */
    private static final Map<String, OverridePathKey> KEYS_BY_SPELLING = indexSpellings();

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
     * The template declared for this key, under {@link #value} or a {@link JsonAlias} spelling —
     * the camelCase value wins when a map declares both — or null when it declares neither.
     */
    @Nullable
    public String findTemplate(Map<String, String> overridePaths) {
        String template = overridePaths.get(value);
        if (template != null) {
            return template;
        }
        for (String alias : aliasesOf(this)) {
            template = overridePaths.get(alias);
            if (template != null) {
                return template;
            }
        }
        return null;
    }

    /**
     * The key with this value or one of its {@link JsonAlias} spellings, or null for one this Core
     * does not know — a config may name a key that only a newer Core understands.
     */
    @Nullable
    public static OverridePathKey find(String value) {
        return KEYS_BY_SPELLING.get(value);
    }

    private static Map<String, OverridePathKey> indexSpellings() {
        Map<String, OverridePathKey> keysBySpelling = new HashMap<>();
        for (OverridePathKey key : values()) {
            keysBySpelling.put(key.value, key);
            for (String alias : aliasesOf(key)) {
                keysBySpelling.put(alias, key);
            }
        }
        return keysBySpelling;
    }

    private static String[] aliasesOf(OverridePathKey key) {
        try {
            JsonAlias alias = OverridePathKey.class.getField(key.name()).getAnnotation(JsonAlias.class);
            return alias == null ? new String[0] : alias.value();
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e); // unreachable: every enum constant is a field of its class
        }
    }
}
