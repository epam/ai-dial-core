package com.epam.aidial.core.server.service;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.HostAndPort;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

@Slf4j
public class WellKnownResourceMetadataService {

    private static final String RESOURCE_METADATA_PATH = "/.well-known/oauth-protected-resource";

    @Getter
    private final List<String> authorizationServers;
    private final String resourceHost;

    public WellKnownResourceMetadataService(JsonObject mcp) {
        JsonObject security = mcp != null ? mcp.getJsonObject("security") : null;

        this.authorizationServers = (security != null && security.containsKey("authorizationServers"))
                                    ? parseAuthorizationServers(security.getValue("authorizationServers"))
                                    : Collections.emptyList();

        this.resourceHost = (security != null) ? security.getString("resourceHost") : null;
    }

    private static List<String> parseAuthorizationServers(Object authServerValue) {
        if (authServerValue instanceof String str) {
            return List.of(str);
        }
        if (authServerValue instanceof JsonArray array) {
            return array.stream()
                .map(Object::toString)
                .toList();
        }
        if (authServerValue == null) {
            return Collections.emptyList();
        }
        throw new IllegalArgumentException("'authorizationServers' must be either a String or an Array");
    }

    public String resolveResourceMetadataPath(HttpServerRequest request) {
        String path = request.path() == null ? "" : request.path();

        HostAndPort authority = request.authority();
        String host = resourceHost != null ? resourceHost : authority.toString();

        return "https://" + host + RESOURCE_METADATA_PATH + path;
    }

    public String resolveResource(HttpServerRequest request) {
        String path = request.path();

        String resourceSegment;

        if (path.equals(RESOURCE_METADATA_PATH)) {
            resourceSegment = ""; // The resource is the root of the host

        } else if (path.startsWith(RESOURCE_METADATA_PATH + "/")) {
            resourceSegment = path.substring(RESOURCE_METADATA_PATH.length());
        } else {
            throw new IllegalArgumentException("Invalid path. Path must be or start with " + RESOURCE_METADATA_PATH + ", but was " + path);
        }

        HostAndPort authority = request.authority();
        String host = resourceHost != null ? resourceHost : authority.toString();

        return "https://" + host + resourceSegment;
    }

}
