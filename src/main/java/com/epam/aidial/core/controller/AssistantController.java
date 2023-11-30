package com.epam.aidial.core.controller;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Assistant;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.data.AssistantData;
import com.epam.aidial.core.data.ListData;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
public class AssistantController {

    private final ProxyContext context;

    public Future<?> getAssistant(String assistantId) {
        Config config = context.getConfig();
        Assistant assistant = config.getAssistant().getAssistants().get(assistantId);

        if (assistant == null) {
            return context.respond(HttpStatus.NOT_FOUND);
        }

        if (!DeploymentController.hasAccess(context, assistant)) {
            return context.respond(HttpStatus.FORBIDDEN);
        }

        AssistantData data = createAssistant(assistant);
        return context.respond(HttpStatus.OK, data);
    }

    public Future<?> getAssistants() {
        Config config = context.getConfig();
        List<AssistantData> assistants = new ArrayList<>();

        for (Assistant assistant : config.getAssistant().getAssistants().values()) {
            if (DeploymentController.hasAccess(context, assistant)) {
                AssistantData data = createAssistant(assistant);
                assistants.add(data);
            }
        }

        ListData<AssistantData> list = new ListData<>();
        list.setData(assistants);

        return context.respond(HttpStatus.OK, list);
    }

    private static AssistantData createAssistant(Assistant assistant) {
        AssistantData data = new AssistantData();
        data.setId(assistant.getName());
        data.setAssistant(assistant.getName());
        data.setDisplayName(assistant.getDisplayName());
        data.setIconUrl(assistant.getIconUrl());
        data.setDescription(assistant.getDescription());
        data.setAddons(assistant.getAddons());
        data.setFeatures(DeploymentController.createFeatures(assistant.getFeatures()));
        data.setInputAttachmentTypes(assistant.getInputAttachmentTypes());
        data.setMaxInputAttachments(assistant.getMaxInputAttachments());
        return data;
    }
}