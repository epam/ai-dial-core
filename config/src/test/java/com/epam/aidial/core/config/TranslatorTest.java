package com.epam.aidial.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.epam.aidial.core.config.InterfaceType.ANTHROPIC_MESSAGES;
import static com.epam.aidial.core.config.InterfaceType.OPENAI_CHAT_COMPLETIONS;
import static com.epam.aidial.core.config.InterfaceType.OPENAI_RESPONSES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code translators} registry and the two shapes an {@code interfaces} entry references one in.
 * Which url a translated interface is served by belongs to {@code DeploymentEndpointUtil}, and linking a
 * name to its registry entry to {@code ConfigPostProcessor}; both are covered by their own tests.
 */
public class TranslatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void translatorCannotBeBuiltWithoutOutOrBaseUrl() {
        // out and baseUrl are what make it a translator: one missing either converts nothing, or converts it
        // nowhere, so the shape is rejected as it is read rather than left to serve 503s
        assertThrows(IllegalArgumentException.class, () -> new Translator(ANTHROPIC_MESSAGES, null, "http://translator"));
        assertThrows(IllegalArgumentException.class, () -> new Translator(ANTHROPIC_MESSAGES, OPENAI_CHAT_COMPLETIONS, null));
        assertThrows(IllegalArgumentException.class, () -> new Translator(ANTHROPIC_MESSAGES, OPENAI_CHAT_COMPLETIONS, ""));
    }

    @Test
    void registryEntryMissingBaseUrlIsRejectedOnRead() throws Exception {
        String json = """
                {
                    "translators": {
                        "anthropicMessagesToOpenaiResponses": {
                            "in": "anthropicMessages",
                            "out": "openaiResponses"
                        }
                    }
                }
                """;

        // absent, so Jackson refuses the creator property
        assertThrows(MismatchedInputException.class, () -> MAPPER.readValue(json, Config.class));

        // present but null, so the constructor refuses it
        String explicitNull = json.replace("\"out\": \"openaiResponses\"", "\"out\": \"openaiResponses\", \"baseUrl\": null");
        assertThrows(ValueInstantiationException.class, () -> MAPPER.readValue(explicitNull, Config.class));
    }

    @Test
    void inlineDefinitionMissingOutIsRejectedOnRead() {
        String json = """
                {
                    "interfaces": {
                        "anthropicMessages": {
                            "mode": "translator",
                            "translator": {"baseUrl": "http://translator"}
                        }
                    }
                }
                """;

        assertThrows(MismatchedInputException.class, () -> MAPPER.readValue(json, Model.class));
    }

    @Test
    void registryEntryIsReadAsInterfaceTypes() throws Exception {
        String json = """
                {
                    "translators": {
                        "anthropicMessagesToOpenaiResponses": {
                            "in": "anthropicMessages",
                            "out": "openaiResponses",
                            "baseUrl": "http://dial-bedrock-translator/to-responses"
                        },
                        "anthropicMessagesToOpenaiChatCompletions": {
                            "in": "anthropicMessages",
                            "out": "openaiChatCompletions",
                            "base_url": "http://dial-bedrock-translator/to-chat-completions"
                        }
                    }
                }
                """;

        Config config = MAPPER.readValue(json, Config.class);

        Translator responses = config.getTranslators().get("anthropicMessagesToOpenaiResponses");
        assertEquals(ANTHROPIC_MESSAGES, responses.getIn());
        assertEquals(OPENAI_RESPONSES, responses.getOut());
        assertEquals("http://dial-bedrock-translator/to-responses", responses.getBaseUrl());
        assertEquals(OPENAI_CHAT_COMPLETIONS, config.getTranslators().get("anthropicMessagesToOpenaiChatCompletions").getOut());
        assertEquals("http://dial-bedrock-translator/to-chat-completions",
                config.getTranslators().get("anthropicMessagesToOpenaiChatCompletions").getBaseUrl());
        // and written back the way they were read, so a round-trip does not rename an interface
        assertTrue(MAPPER.writeValueAsString(responses).contains("\"out\":\"openaiResponses\""));
    }

    @Test
    void registryEntryNamingAnInterfaceThisCoreDoesNotKnowIsRejectedOnRead() {
        // unlike an interfaces key, which is inert when this Core does not know it, in and out are matched
        // against the interface the translator is referenced from and the one the deployment serves back
        String json = """
                {
                    "translators": {
                        "geminiInteractionsToOpenaiResponses": {
                            "in": "geminiInteractions",
                            "out": "openaiResponses",
                            "baseUrl": "http://dial-vertexai-translator/to-responses"
                        }
                    }
                }
                """;

        assertThrows(InvalidFormatException.class, () -> MAPPER.readValue(json, Config.class));
    }

    @Test
    void namedTranslatorKeepsItsName() throws Exception {
        String json = """
                {
                    "interfaces": {
                        "anthropicMessages": {
                            "mode": "translator",
                            "translator": "anthropicMessagesToOpenaiChatCompletions"
                        }
                    }
                }
                """;

        Model model = MAPPER.readValue(json, Model.class);

        TranslatorRef translator = translatorOf(model);
        assertEquals("anthropicMessagesToOpenaiChatCompletions", translator.getName());
        // a name is linked to its registry entry at config load, not while parsing
        assertNull(translator.getDefinition());
        // and is written back as a name, so the reference is not frozen into a copy by a round-trip
        assertTrue(MAPPER.writeValueAsString(model)
                .contains("\"translator\":\"anthropicMessagesToOpenaiChatCompletions\""), json);
    }

    @Test
    void inlineTranslatorStaysInline() throws Exception {
        String json = """
                {
                    "interfaces": {
                        "anthropicMessages": {
                            "mode": "translator",
                            "translator": {
                                "out": "openaiChatCompletions",
                                "baseUrl": "http://some-custom-translator/to-chat-completions"
                            }
                        }
                    }
                }
                """;

        Model restored = MAPPER.readValue(MAPPER.writeValueAsString(MAPPER.readValue(json, Model.class)), Model.class);

        TranslatorRef translator = translatorOf(restored);
        assertNull(translator.getName());
        assertEquals(OPENAI_CHAT_COMPLETIONS, translator.getDefinition().getOut());
        assertEquals("http://some-custom-translator/to-chat-completions", translator.getDefinition().getBaseUrl());
        // in is implied by the entry the definition sits under
        assertNull(translator.getDefinition().getIn());
    }

    @Test
    void translatorIsOmittedWhenAbsent() throws Exception {
        Model model = new Model();
        model.setInterfaces(Map.of(ANTHROPIC_MESSAGES.getValue(), new DeploymentInterface("http://anthropic")));

        String json = MAPPER.writeValueAsString(model);

        assertFalse(json.contains("translator"), json);
        assertFalse(json.contains("translators"), json);
    }

    private static TranslatorRef translatorOf(Model model) {
        return model.getInterfaces().get(ANTHROPIC_MESSAGES.getValue()).getTranslator();
    }
}
