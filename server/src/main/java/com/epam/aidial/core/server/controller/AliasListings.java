package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Alias;
import com.epam.aidial.core.config.AliasPresentation;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.service.DeploymentService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The alias part of the model listings that otherwise answer from memory on the event loop ({@code /openai/models},
 * {@code /openai/deployments}, {@code /anthropic/v1/models}). Stored aliases are listed and read from storage, and an
 * alias view may read its target, so it is built on the task executor.
 */
@Slf4j
@UtilityClass
public class AliasListings {

    /**
     * Whether a single-item endpoint must look {@code id} up as an alias: the name of a config alias, or the url of a
     * stored one.
     */
    public boolean isAliasId(Config config, String id) {
        return config.getAliases().containsKey(id) || DeploymentService.isStoredAliasId(id);
    }

    /**
     * The views of the model aliases the caller may use, config and stored.
     */
    public Future<List<Model>> listModels(ProxyContext context) {
        return listModels(context, context.getProxy().getDeploymentService().storedAliasesOnce());
    }

    /**
     * {@link #listModels} with the stored aliases a request that lists several types reads once.
     */
    public Future<List<Model>> listModels(ProxyContext context, Supplier<List<Alias>> stored) {
        return context.getProxy().getTaskExecutor().submit(() -> {
            List<Model> models = new ArrayList<>();
            for (Deployment view : context.getProxy().getDeploymentService().listAliasViews(context, AliasPresentation.MODEL, stored)) {
                models.add((Model) view);
            }
            return models;
        });
    }

    /**
     * Answers for the model alias {@code id}: {@code mapper} of its view, 404 when it has none or {@code listed} leaves
     * it out, 403 when the caller may not use it.
     */
    public void respondModel(ProxyContext context, String id, Predicate<Model> listed, Function<Model, Object> mapper) {
        context.getProxy().getTaskExecutor()
                .submit(() -> (Model) context.getProxy().getDeploymentService().findAliasView(context, id, AliasPresentation.MODEL))
                .onSuccess(model -> {
                    if (model == null || !listed.test(model)) {
                        context.respond(HttpStatus.NOT_FOUND);
                    } else {
                        context.respond(HttpStatus.OK, mapper.apply(model));
                    }
                })
                .onFailure(error -> respondError(context, error));
    }

    public void respondError(ProxyContext context, Throwable error) {
        if (error instanceof PermissionDeniedException) {
            context.respond(HttpStatus.FORBIDDEN);
            return;
        }
        log.error("Can't list aliases", error);
        context.respond(error, "Internal error");
    }
}
