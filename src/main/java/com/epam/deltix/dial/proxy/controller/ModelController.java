package com.epam.deltix.dial.proxy.controller;

import com.epam.deltix.dial.proxy.ProxyContext;
import com.epam.deltix.dial.proxy.config.Config;
import com.epam.deltix.dial.proxy.config.Model;
import com.epam.deltix.dial.proxy.config.ModelType;
import com.epam.deltix.dial.proxy.data.ListData;
import com.epam.deltix.dial.proxy.data.ModelData;
import com.epam.deltix.dial.proxy.util.HttpStatus;
import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
public class ModelController {

    private final ProxyContext context;

    public Future<?> getModel(String modelId) {
        Config config = context.getConfig();
        Model model = config.getModels().get(modelId);

        if (model == null) {
            return context.respond(HttpStatus.NOT_FOUND);
        }

        if (!DeploymentController.hasAccess(context, model)) {
            return context.respond(HttpStatus.FORBIDDEN);
        }

        ModelData data = createModel(model);
        return context.respond(HttpStatus.OK, data);
    }

    public Future<?> getModels() {
        Config config = context.getConfig();
        List<ModelData> models = new ArrayList<>();

        for (Model model : config.getModels().values()) {
            if (DeploymentController.hasAccess(context, model)) {
                ModelData data = createModel(model);
                models.add(data);
            }
        }

        ListData<ModelData> list = new ListData<>();
        list.setData(models);

        return context.respond(HttpStatus.OK, list);
    }

    private static ModelData createModel(Model model) {
        ModelData data = new ModelData();
        data.setId(model.getName());
        data.setModel(model.getName());
        data.setDisplayName(model.getDisplayName());
        data.setIconUrl(model.getIconUrl());
        data.setDescription(model.getDescription());

        if (model.getType() == ModelType.EMBEDDING) {
            data.getCapabilities().setEmbeddings(true);
        } else if (model.getType() == ModelType.COMPLETION) {
            data.getCapabilities().setCompletion(true);
        } else if (model.getType() == ModelType.CHAT) {
            data.getCapabilities().setChatCompletion(true);
        }

        return data;
    }
}