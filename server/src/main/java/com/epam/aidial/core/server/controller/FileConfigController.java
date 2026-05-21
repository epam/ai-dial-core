package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;
import lombok.RequiredArgsConstructor;

import java.util.Map;

/**
 * Read-only operator surface for file-sourced configuration entries. Per slice U.1:
 * the per-entity Configuration API ({@code /v1/{type}/{bucket}/{name}}) is blob-only,
 * so operators inspect file entries through this dedicated path.
 *
 * <p>Authorization: admin role for every supported type EXCEPT {@code keys}, which requires
 * the security-admin tier — file-sourced {@code Config.keys} keeps the legacy
 * map-key-as-secret format per OQ-12, so leaking key names via URL/listing exposes secrets
 * to anyone with the admin role.
 *
 * <p>Singleton settings: {@code GET /v1/admin/config/file/settings/global} returns the
 * file-defined (or schema-default) values for {@code globalInterceptors} and
 * {@code retriableErrorCodes} regardless of whether an API blob exists — this surface
 * is the file/default view; the blob projection lives on {@code /v1/settings/platform/global}.
 *
 * <p>Read-only by design — {@code aidial.config.json} remains the operator-managed source of
 * truth for file entries. {@code POST}/{@code PUT}/{@code DELETE} are wired through the router
 * so the controller can emit {@code 405 Method Not Allowed} with {@code Allow: GET}
 * (RFC 9110 §15.5.6), rather than falling through to the global route handler.
 */
@RequiredArgsConstructor
public class FileConfigController implements Controller {

    private static final String SETTINGS_SINGLETON_NAME = "global";
    private static final String ALLOW_GET = "GET";

    private final ProxyContext context;
    private final ConfigAuthorizationService authorizationService;
    private final MergedConfigStore mergedConfigStore;
    private final String entityType;
    private final String name;

    @Override
    public Future<?> handle() {
        HttpMethod method = context.getRequest().method();
        if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
            context.putHeader("Allow", ALLOW_GET);
            context.respond(HttpStatus.METHOD_NOT_ALLOWED, "Not implemented");
            return Future.succeededFuture();
        }

        // The route regex restricts {type} to a closed set; ResourceTypes.of() cannot throw here.
        ResourceTypes resourceType = ResourceTypes.of(entityType);
        boolean securityAdmin = authorizationService.isSecurityAdmin(context);

        // PROJECT_KEY requires security-admin specifically (file map keys equal secrets per OQ-12).
        // All other types accept either admin or security-admin (security-admin is strictly stronger).
        if (resourceType == ResourceTypes.PROJECT_KEY) {
            if (!securityAdmin) {
                context.respond(HttpStatus.FORBIDDEN,
                        "Access to file-sourced keys requires security-admin role");
                return Future.succeededFuture();
            }
        } else if (!securityAdmin && !authorizationService.isAdmin(context)) {
            context.respond(HttpStatus.FORBIDDEN, "Forbidden");
            return Future.succeededFuture();
        }

        boolean revealSecrets = "true".equals(context.getRequest().getParam("reveal_secrets"));
        if (revealSecrets && !securityAdmin) {
            context.respond(HttpStatus.FORBIDDEN, "reveal_secrets requires security-admin role");
            return Future.succeededFuture();
        }

        // Read directly from the file-sourced Config — independent of the API overlay in the
        // merged Config. This keeps the file-config surface authoritative for file entries and
        // means listings / single GETs cannot accidentally project API-managed entries.
        Config fileConfig = mergedConfigStore.getFileSourcedConfig();
        if (fileConfig == null) {
            // FileConfigStore not yet initialised — only happens during startup before
            // MergedConfigStore.init(). Treat as "no file entries" but still dispatch per-shape:
            // a single-entity GET on an unknown name is 404, not an empty listing.
            if (resourceType == ResourceTypes.GLOBAL_SETTINGS) {
                return handleSettings(new Config());
            }
            if (name == null || name.isEmpty()) {
                // putArray returns the child ArrayNode — keep the parent reference so the response
                // serializes as the {"items":[]} envelope, not a bare [] array.
                ObjectNode body = ProxyUtil.MAPPER.createObjectNode();
                body.putArray("items");
                context.respond(HttpStatus.OK, body);
            } else {
                context.respond(HttpStatus.NOT_FOUND);
            }
            return Future.succeededFuture();
        }
        if (resourceType == ResourceTypes.GLOBAL_SETTINGS) {
            return handleSettings(fileConfig);
        }
        if (name == null || name.isEmpty()) {
            return handleList(fileConfig, resourceType);
        }
        return handleSingle(fileConfig, resourceType, revealSecrets);
    }

    private Future<?> handleList(Config fileConfig, ResourceTypes resourceType) {
        Map<String, ?> source = entitySource(fileConfig, resourceType);
        if (source == null) {
            context.respond(HttpStatus.NOT_FOUND);
            return Future.succeededFuture();
        }
        ObjectNode body = ProxyUtil.MAPPER.createObjectNode();
        ArrayNode items = body.putArray("items");
        for (String key : source.keySet()) {
            items.addObject().put("name", key);
        }
        context.respond(HttpStatus.OK, body);
        return Future.succeededFuture();
    }

    private Future<?> handleSingle(Config fileConfig, ResourceTypes resourceType, boolean revealSecrets) {
        Map<String, ?> source = entitySource(fileConfig, resourceType);
        if (source == null) {
            context.respond(HttpStatus.NOT_FOUND);
            return Future.succeededFuture();
        }
        Object item = source.get(name);
        if (item == null) {
            context.respond(HttpStatus.NOT_FOUND);
            return Future.succeededFuture();
        }

        if (resourceType == ResourceTypes.APP_TYPE_SCHEMA) {
            String json = (String) item;
            try {
                JsonNode schema = ProxyUtil.MAPPER.readTree(json);
                ObjectNode node = ProxyUtil.MAPPER.createObjectNode();
                if (schema.isObject()) {
                    node.setAll((ObjectNode) schema);
                } else {
                    node.set("schema", schema);
                }
                node.put("name", name);
                node.put("status", "valid");
                context.respond(HttpStatus.OK, node);
            } catch (Exception e) {
                context.respond(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
            }
            return Future.succeededFuture();
        }

        // BLOB_MAPPER pass-through for security-admin reveal flow; the default MAPPER applies the
        // masking serializer modifier and emits "***" for @EncryptedField values.
        var mapper = revealSecrets ? ProxyUtil.BLOB_MAPPER : ProxyUtil.MAPPER;
        ObjectNode node = mapper.valueToTree(item);
        node.put("name", name);
        node.put("status", "valid");
        context.respond(HttpStatus.OK, node);
        return Future.succeededFuture();
    }

    private Future<?> handleSettings(Config fileConfig) {
        if (name != null && !name.isEmpty() && !SETTINGS_SINGLETON_NAME.equals(name)) {
            context.respond(HttpStatus.NOT_FOUND);
            return Future.succeededFuture();
        }
        ObjectNode body = ProxyUtil.MAPPER.createObjectNode();
        if (name == null || name.isEmpty()) {
            // Listing form — synthesize a single-item entry pointing at "global".
            body.putArray("items").addObject().put("name", SETTINGS_SINGLETON_NAME);
            context.respond(HttpStatus.OK, body);
            return Future.succeededFuture();
        }
        body.set("globalInterceptors", ProxyUtil.MAPPER.valueToTree(fileConfig.getGlobalInterceptors()));
        body.set("retriableErrorCodes", ProxyUtil.MAPPER.valueToTree(fileConfig.getRetriableErrorCodes()));
        body.put("name", SETTINGS_SINGLETON_NAME);
        body.put("status", "valid");
        context.respond(HttpStatus.OK, body);
        return Future.succeededFuture();
    }

    private static Map<String, ?> entitySource(Config config, ResourceTypes resourceType) {
        return switch (resourceType) {
            case MODEL -> config.getModels();
            case INTERCEPTOR -> config.getInterceptors();
            case ROLE -> config.getRoles();
            case PROJECT_KEY -> config.getKeys();
            case ROUTE -> config.getRoutes();
            case APP_TYPE_SCHEMA -> config.getApplicationTypeSchemas();
            // GLOBAL_SETTINGS has no per-entity map — handled by handleSettings before this helper.
            // Other types (APPLICATION, TOOL_SET, FILE, PROMPT, CONVERSATION) are not part of the
            // file-config surface; the route regex restricts {type} to the closed set above.
            default -> null;
        };
    }
}
