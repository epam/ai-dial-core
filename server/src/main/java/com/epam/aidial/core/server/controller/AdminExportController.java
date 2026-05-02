package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.vertx.core.Future;
import io.vertx.core.http.HttpHeaders;

import java.util.Map;

/**
 * Admin-only snapshot of the in-memory {@link Config}. Default JSON; YAML when the request asks
 * for it via {@code ?format=yaml} or an {@code Accept: application/yaml} header.
 *
 * <p>Phase 1 emits whatever Jackson serializes from {@link Config} plus a manually re-attached
 * {@code keys} map with the {@code key} field masked — Config's map field carries
 * {@code @JsonProperty(WRITE_ONLY)} which suppresses the field at serialization time. Phase 2's
 * dual-mapper plumbing replaces this manual step.
 */
public class AdminExportController implements Controller {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final String YAML_CONTENT_TYPE = "application/yaml";

    private final ProxyContext context;
    private final ConfigAuthorizationService authorizationService;

    public AdminExportController(ProxyContext context, ConfigAuthorizationService authorizationService) {
        this.context = context;
        this.authorizationService = authorizationService;
    }

    @Override
    public Future<?> handle() throws Exception {
        if (!authorizationService.isAdmin(context)) {
            context.respond(HttpStatus.FORBIDDEN, "Forbidden");
            return Future.succeededFuture();
        }
        ObjectNode body = buildExport(context.getConfig());
        if (isYamlRequested()) {
            String yaml = YAML_MAPPER.writeValueAsString(body);
            context.putHeader(HttpHeaders.CONTENT_TYPE, YAML_CONTENT_TYPE)
                    .respond(HttpStatus.OK, yaml);
        } else {
            context.respond(HttpStatus.OK, body);
        }
        return Future.succeededFuture();
    }

    private ObjectNode buildExport(Config config) throws JsonProcessingException {
        // Round-trip via JSON string — applicationTypeSchemas uses a custom serializer that calls
        // writeRaw, which TokenBuffer (used by valueToTree) does not support.
        String json = ProxyUtil.MAPPER.writeValueAsString(config);
        ObjectNode body = (ObjectNode) ProxyUtil.MAPPER.readTree(json);
        ObjectNode keys = body.putObject("keys");
        for (Map.Entry<String, Key> entry : config.getKeys().entrySet()) {
            ObjectNode keyNode = ProxyUtil.MAPPER.valueToTree(entry.getValue());
            if (keyNode.has("key")) {
                keyNode.put("key", "***");
            }
            keys.set(entry.getKey(), keyNode);
        }
        return body;
    }

    private boolean isYamlRequested() {
        String fmt = context.getRequest().getParam("format");
        if ("yaml".equalsIgnoreCase(fmt)) {
            return true;
        }
        String accept = context.getRequest().getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.toLowerCase().contains("yaml");
    }
}
