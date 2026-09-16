package com.epam.aidial.core.server.controller.anthropic;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.TokenLimits;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.anthropic.AnthropicModelData;
import com.epam.aidial.core.server.data.anthropic.AnthropicModelListData;
import com.epam.aidial.core.server.util.DeploymentEndpointUtil;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Anthropic-compatible models listing, scoped to the models that actually serve the
 * {@link InterfaceType#ANTHROPIC_MESSAGES} interface (i.e. are reachable via {@link MessagesController}).
 * Mirrors {@link com.epam.aidial.core.server.controller.ModelController}, shaped like Anthropic's
 * <a href="https://platform.claude.com/docs/en/api/models/list">models list API</a>.
 */
@RequiredArgsConstructor
public class AnthropicModelController {

    private static final long DEFAULT_CREATED_AT_SECONDS = 1672534800L;
    private static final int DEFAULT_PAGE_LIMIT = 20;
    private static final int MIN_PAGE_LIMIT = 1;
    private static final int MAX_PAGE_LIMIT = 1000;

    private final ProxyContext context;

    @ApiOperation(
            method = "GET",
            path = "/anthropic/v1/models/{model_name}",
            operationId = "getAnthropicModel",
            tags = {"Deployment listing"},
            parameters = {
                    @ApiParameter(name = "model_name", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.MODEL_NAME),
                    @ApiParameter(name = "anthropic-beta", in = ParameterIn.HEADER, required = false,
                            description = "Optional beta feature opt-in header(s); DIAL accepts and ignores it for this endpoint.")
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = AnthropicModelData.class)),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404)
            }
    )
    public Future<?> getModel(String modelId) {
        Config config = context.getConfig();
        Model model = config.getModels().get(modelId);

        if (model == null || !DeploymentEndpointUtil.isInterfaceDeclared(model, InterfaceType.ANTHROPIC_MESSAGES)) {
            return context.respond(HttpStatus.NOT_FOUND);
        }

        if (!model.hasAccess(context.getUserRoles())) {
            return context.respond(HttpStatus.FORBIDDEN);
        }

        AnthropicModelData data = createModel(model, config.getDefaultLocale());
        return context.respond(HttpStatus.OK, data);
    }

    @ApiOperation(
            method = "GET",
            path = "/anthropic/v1/models",
            operationId = "getAnthropicModels",
            tags = {"Deployment listing"},
            parameters = {
                    @ApiParameter(name = "after_id", in = ParameterIn.QUERY, required = false,
                            description = "ID of the model to use as a cursor; returns the page immediately after it."),
                    @ApiParameter(name = "before_id", in = ParameterIn.QUERY, required = false,
                            description = "ID of the model to use as a cursor; returns the page immediately before it."),
                    @ApiParameter(name = "limit", in = ParameterIn.QUERY, required = false, schema = Integer.class,
                            description = "Number of items to return per page. Defaults to 20. Ranges from 1 to 1000."),
                    @ApiParameter(name = "anthropic-beta", in = ParameterIn.HEADER, required = false,
                            description = "Optional beta feature opt-in header(s); DIAL accepts and ignores it for this endpoint.")
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = AnthropicModelListData.class)),
                    @ApiResponse(code = 400)
            }
    )
    public Future<?> getModels() {
        Config config = context.getConfig();
        List<AnthropicModelData> models = new ArrayList<>();

        for (Model model : config.getModels().values()) {
            if (model.hasAccess(context.getUserRoles())
                    && DeploymentEndpointUtil.isInterfaceDeclared(model, InterfaceType.ANTHROPIC_MESSAGES)) {
                models.add(createModel(model, config.getDefaultLocale()));
            }
        }

        String afterId = context.getRequest().getParam("after_id");
        String beforeId = context.getRequest().getParam("before_id");

        if (afterId != null && beforeId != null) {
            return context.respond(HttpStatus.BAD_REQUEST, "Only one of after_id or before_id may be provided");
        }

        int limit;
        try {
            limit = Integer.parseInt(context.getRequest().getParam("limit", String.valueOf(DEFAULT_PAGE_LIMIT)));
        } catch (NumberFormatException e) {
            return context.respond(HttpStatus.BAD_REQUEST, "Limit must be an integer");
        }
        if (limit < MIN_PAGE_LIMIT || limit > MAX_PAGE_LIMIT) {
            return context.respond(HttpStatus.BAD_REQUEST,
                    "Limit is out of allowed range: [%d, %d]".formatted(MIN_PAGE_LIMIT, MAX_PAGE_LIMIT));
        }

        AnthropicModelListData list;
        try {
            list = paginate(models, afterId, beforeId, limit);
        } catch (IllegalArgumentException e) {
            return context.respond(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        return context.respond(HttpStatus.OK, list);
    }

    private static AnthropicModelListData paginate(
            List<AnthropicModelData> models, String afterId, String beforeId, int limit) {
        AnthropicModelListData list = new AnthropicModelListData();
        List<AnthropicModelData> page;
        boolean hasMore;

        if (afterId != null) {
            int cursor = indexOf(models, afterId, "after_id");
            int from = cursor + 1;
            int to = Math.min(from + limit, models.size());
            page = models.subList(from, to);
            hasMore = to < models.size();
        } else if (beforeId != null) {
            int cursor = indexOf(models, beforeId, "before_id");
            int to = cursor;
            int from = Math.max(to - limit, 0);
            page = models.subList(from, to);
            hasMore = from > 0;
        } else {
            int to = Math.min(limit, models.size());
            page = models.subList(0, to);
            hasMore = to < models.size();
        }

        list.setData(page);
        list.setHasMore(hasMore);
        if (!page.isEmpty()) {
            list.setFirstId(page.get(0).getId());
            list.setLastId(page.get(page.size() - 1).getId());
        }
        return list;
    }

    private static int indexOf(List<AnthropicModelData> models, String id, String paramName) {
        for (int i = 0; i < models.size(); i++) {
            if (models.get(i).getId().equals(id)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Invalid " + paramName + ": model not found");
    }

    static AnthropicModelData createModel(Model model, String defaultLocale) {
        AnthropicModelData data = new AnthropicModelData();
        data.setId(model.getName());
        data.setDisplayName(model.getDisplayName() != null
                ? model.getDisplayName().resolve(defaultLocale, defaultLocale)
                : model.getName());

        Long createdAt = model.getCreatedAt();
        data.setCreatedAt(Instant.ofEpochSecond(createdAt != null ? createdAt : DEFAULT_CREATED_AT_SECONDS).toString());

        TokenLimits limits = model.getLimits();
        if (limits != null) {
            data.setMaxInputTokens(limits.getMaxPromptTokens());
            data.setMaxTokens(limits.getMaxCompletionTokens());
        }

        return data;
    }
}
