package com.epam.aidial.core.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ModelTest {

    @Test
    void resolveFieldsHashingOrder_chatCompletions_usesConfiguredOverride() {
        Model model = new Model();
        List<String> override = List.of("prefix.body.messages");
        model.setFieldsHashingOrder(override);

        List<String> resolved = model.resolveFieldsHashingOrder(InterfaceType.OPENAI_CHAT_COMPLETIONS);

        assertSame(override, resolved);
    }

    @Test
    void resolveFieldsHashingOrder_anthropicMessages_ignoresConfiguredOverride() {
        Model model = new Model();
        model.setFieldsHashingOrder(List.of("prefix.body.messages"));

        List<String> resolved = model.resolveFieldsHashingOrder(InterfaceType.ANTHROPIC_MESSAGES);

        assertEquals(List.of("prefix.body.tools", "prefix.body.system", "prefix.body.messages"), resolved);
    }

    @Test
    void resolveFieldsHashingOrder_responses_ignoresConfiguredOverride() {
        Model model = new Model();
        model.setFieldsHashingOrder(List.of("prefix.body.messages"));

        List<String> resolved = model.resolveFieldsHashingOrder(InterfaceType.OPENAI_RESPONSES);

        assertEquals(List.of("prefix.body.tools", "prefix.body.instructions", "prefix.body.input"), resolved);
    }
}
