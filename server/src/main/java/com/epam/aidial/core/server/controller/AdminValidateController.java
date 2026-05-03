package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.SecretFieldProcessor;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Admin-only configuration-validation endpoint at {@code POST /v1/admin/validate}.
 * Phase 2 scope (design 03 §6) is model-only: Jackson parse, mask-sentinel rejection,
 * deployment-name uniqueness, upstream URL syntax. Validation is non-mutating —
 * never triggers a config rebuild or touches storage.
 */
public class AdminValidateController implements Controller {

    private static final String MODEL_KIND = "Model";

    private final ProxyContext context;
    private final ConfigAuthorizationService authorizationService;
    private final SecretFieldProcessor secretFieldProcessor;

    public AdminValidateController(ProxyContext context,
                                   ConfigAuthorizationService authorizationService,
                                   SecretFieldProcessor secretFieldProcessor) {
        this.context = context;
        this.authorizationService = authorizationService;
        this.secretFieldProcessor = secretFieldProcessor;
    }

    @Override
    public Future<?> handle() throws Exception {
        if (!authorizationService.isAdmin(context)) {
            context.respond(HttpStatus.FORBIDDEN, "Forbidden");
            return Future.succeededFuture();
        }

        context.getRequest().body()
                .onSuccess(this::process)
                .onFailure(error -> context.respond(HttpStatus.BAD_REQUEST,
                        "Failed to read request body: " + error.getMessage()));
        return Future.succeededFuture();
    }

    private void process(Buffer body) {
        JsonNode envelope;
        try {
            String text = body == null ? "" : body.toString(StandardCharsets.UTF_8);
            envelope = ProxyUtil.MAPPER.readTree(text.isEmpty() ? "{}" : text);
        } catch (JsonProcessingException e) {
            context.respond(HttpStatus.BAD_REQUEST, "Invalid JSON at " + locationOf(e));
            return;
        }
        if (!envelope.isObject()) {
            context.respond(HttpStatus.BAD_REQUEST, "Request body must be a JSON object");
            return;
        }

        JsonNode kindNode = envelope.get("kind");
        if (kindNode == null || !kindNode.isTextual()) {
            context.respond(HttpStatus.BAD_REQUEST, "Missing or invalid 'kind'");
            return;
        }
        String kind = kindNode.asText();
        if (!MODEL_KIND.equals(kind)) {
            context.respond(HttpStatus.BAD_REQUEST, "Unsupported kind: " + kind);
            return;
        }

        JsonNode nameNode = envelope.get("name");
        if (nameNode == null || !nameNode.isTextual() || nameNode.asText().isBlank()) {
            context.respond(HttpStatus.BAD_REQUEST, "Missing or invalid 'name'");
            return;
        }
        String name = nameNode.asText();

        JsonNode specNode = envelope.get("spec");
        if (specNode == null || !specNode.isObject()) {
            context.respond(HttpStatus.BAD_REQUEST, "Missing or invalid 'spec'");
            return;
        }

        ArrayNode errors = ProxyUtil.MAPPER.createArrayNode();
        Model model = parseModel(specNode, errors);

        try {
            secretFieldProcessor.validateNoMaskSentinel(specNode, Model.class);
        } catch (IllegalArgumentException e) {
            addError(errors, null, e.getMessage());
        }

        Config config = context.getConfig();
        if (config != null && deploymentExists(config, name)) {
            addError(errors, "name", "Deployment name '" + name + "' is already in use");
        }

        if (model != null) {
            validateUpstreams(model.getUpstreams(), errors);
        }

        ObjectNode response = ProxyUtil.MAPPER.createObjectNode();
        response.put("valid", errors.isEmpty());
        response.set("errors", errors);
        context.respond(HttpStatus.OK, response);
    }

    /**
     * selectDeployment matches simple-name keys (file-defined entities). API-created models are
     * keyed by canonical ID ({@code models/public/<name>}) by MergedConfigStore, so probe both.
     * Applications and toolsets are not MergedConfigStore-managed (design 02 §6), so simple-name
     * lookup already covers them.
     */
    private static boolean deploymentExists(Config config, String name) {
        if (config.selectDeployment(name) != null) {
            return true;
        }
        return config.getModels() != null && config.getModels().containsKey("models/public/" + name);
    }

    private Model parseModel(JsonNode specNode, ArrayNode errors) {
        try {
            return ProxyUtil.BLOB_MAPPER.treeToValue(specNode, Model.class);
        } catch (JsonProcessingException e) {
            // Suppress getOriginalMessage() — Jackson echoes the offending token value verbatim,
            // which can leak submitted secrets back to the caller and into server logs.
            String field = e instanceof JsonMappingException jme ? jme.getPathReference() : null;
            addError(errors, field, "Failed to parse Model at " + locationOf(e));
            return null;
        }
    }

    private static String locationOf(JsonProcessingException e) {
        return e.getLocation() == null
                ? "unknown location"
                : "line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr();
    }

    private static void validateUpstreams(List<Upstream> upstreams, ArrayNode errors) {
        if (upstreams == null) {
            return;
        }
        for (int i = 0; i < upstreams.size(); i++) {
            Upstream upstream = upstreams.get(i);
            if (upstream == null) {
                continue;
            }
            checkUrl(upstream.getEndpoint(), "upstreams[" + i + "].endpoint", errors);
            checkUrl(upstream.getResponsesEndpoint(), "upstreams[" + i + "].responsesEndpoint", errors);
        }
    }

    private static void checkUrl(String url, String field, ArrayNode errors) {
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            new URI(url).toURL();
        } catch (MalformedURLException | URISyntaxException | IllegalArgumentException e) {
            addError(errors, field, "Malformed URL: " + e.getMessage());
        }
    }

    private static void addError(ArrayNode errors, String field, String message) {
        ObjectNode entry = errors.addObject();
        if (field != null) {
            entry.put("field", field);
        }
        entry.put("message", message);
    }
}
