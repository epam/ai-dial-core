package com.epam.aidial.core.openapi;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.controller.ControllerSelector;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Validates endpoint metadata against registered routes and OpenAPI requirements.
 */
public final class EndpointValidator {

    private record RouteInfo(String method, Pattern pattern) {}

    private EndpointValidator() {
    }

    /**
     * Validates endpoint metadata against registered routes and OpenAPI requirements.
     *
     * @param endpoints List of endpoints to validate
     * @throws IllegalStateException if validation fails
     */
    public static void validate(List<EndpointMetadata.Endpoint> endpoints) {
        validatePathsMatchRoutes(endpoints);
        validateUniqueOperationIds(endpoints);
    }

    /**
     * Validates that every @ApiOperation path matches a registered route in ControllerSelector.
     *
     * @param endpoints List of endpoints to validate
     * @throws IllegalStateException if any endpoint has no matching route
     */
    private static void validatePathsMatchRoutes(List<EndpointMetadata.Endpoint> endpoints) {
        List<RouteInfo> routes = getControllerRoutes();

        List<String> unmatchedEndpoints = new ArrayList<>();

        for (EndpointMetadata.Endpoint endpoint : endpoints) {
            if (isProxyHandledEndpoint(endpoint)) {
                continue;
            }

            String samplePath = RouteExtractor.toSamplePath(endpoint.path());
            String httpMethod = endpoint.method();
            boolean matched = false;

            for (RouteInfo route : routes) {
                if (route.method.equals(httpMethod) && route.pattern.matcher(samplePath).find()) {
                    matched = true;
                    break;
                }
            }

            if (!matched) {
                unmatchedEndpoints.add(httpMethod + " " + endpoint.path());
            }
        }

        if (!unmatchedEndpoints.isEmpty()) {
            throw new IllegalStateException(
                    "OpenAPI spec generation failed: The following @ApiOperation annotations do not match any registered route in ControllerSelector:\n  "
                            + String.join("\n  ", unmatchedEndpoints)
                            + "\n\nEach @ApiOperation(method, path) must correspond to an actual route."
            );
        }
    }

    /**
     * Validates that all operationIds are unique.
     *
     * @param endpoints List of endpoints to validate
     * @throws IllegalStateException if duplicate operationIds are found
     */
    private static void validateUniqueOperationIds(List<EndpointMetadata.Endpoint> endpoints) {
        Map<String, List<EndpointMetadata.Endpoint>> operationIdMap = new HashMap<>();

        for (EndpointMetadata.Endpoint endpoint : endpoints) {
            operationIdMap.computeIfAbsent(endpoint.operationId(), k -> new ArrayList<>()).add(endpoint);
        }

        List<String> duplicates = operationIdMap.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> {
                    String operationId = entry.getKey();
                    String conflictingEndpoints = entry.getValue().stream()
                            .map(ep -> ep.method() + " " + ep.path())
                            .collect(Collectors.joining("\n    - "));
                    return "Duplicate operationId: " + operationId + "\n  Found in:\n    - " + conflictingEndpoints;
                })
                .collect(Collectors.toList());

        if (!duplicates.isEmpty()) {
            throw new IllegalStateException(
                    "OpenAPI spec generation failed: Duplicate operationId values detected:\n\n"
                            + String.join("\n\n", duplicates)
                            + "\n\nThe OpenAPI specification requires operationId values to be unique."
            );
        }
    }

    /**
     * Endpoints handled in {@link Proxy} before {@link ControllerSelector} (health, version, OAuth metadata).
     */
    private static boolean isProxyHandledEndpoint(EndpointMetadata.Endpoint endpoint) {
        if (!"GET".equals(endpoint.method())) {
            return false;
        }
        String path = endpoint.path();
        if (Proxy.HEALTH_CHECK_PATH.equals(path) || Proxy.VERSION_PATH.equals(path)) {
            return true;
        }
        String samplePath = RouteExtractor.toSamplePath(path);
        return Proxy.TOOLSET_PROXY_METADATA_PATTERN.matcher(samplePath).matches()
                || Proxy.APPLICATION_MCP_PROXY_METADATA_PATTERN.matcher(samplePath).matches();
    }

    /**
     * Extracts route information from ControllerSelector via reflection.
     * Note: Does NOT apply exclusions - all registered routes are returned.
     * Exclusions apply only when checking if routes lack annotations, not when validating
     * that existing annotations match routes.
     */
    private static List<RouteInfo> getControllerRoutes() {
        try {
            Field routesField = ControllerSelector.class.getDeclaredField("ROUTES");
            routesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<?> routes = (List<?>) routesField.get(null);

            List<RouteInfo> routeInfos = new ArrayList<>();
            for (Object route : routes) {
                String method = getRouteMethodName(route);
                Pattern pattern = getRoutePattern(route);
                routeInfos.add(new RouteInfo(method, pattern));
            }
            return routeInfos;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to extract routes from ControllerSelector", e);
        }
    }

    private static String getRouteMethodName(Object route) throws Exception {
        Method accessor = route.getClass().getDeclaredMethod("method");
        accessor.setAccessible(true);
        Object httpMethod = accessor.invoke(route);
        return httpMethod.toString();
    }

    private static Pattern getRoutePattern(Object route) throws Exception {
        Method accessor = route.getClass().getDeclaredMethod("pathPattern");
        accessor.setAccessible(true);
        return (Pattern) accessor.invoke(route);
    }
}
