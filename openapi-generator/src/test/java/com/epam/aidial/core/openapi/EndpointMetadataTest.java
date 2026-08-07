package com.epam.aidial.core.openapi;


import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.controller.ControllerSelector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndpointMetadataTest {

    /**
     * Routes intentionally excluded from OpenAPI documentation.
     * Use null method to exclude all HTTP methods for a given pattern.
     */
    private static final List<RouteExclusion> EXCLUDED_ROUTES = List.of(
            // DEPLOYMENT_ROUTES: Dynamic pass-through for custom application routes
            new RouteExclusion(null, Pattern.compile(".*deployments.*route.*")),

            // MCP proxy endpoints: Documented via OAuth metadata endpoints (.well-known URLs)
            new RouteExclusion(null, Pattern.compile(".*/toolset/.*/mcp.*")),       // TOOL_SET_MCP_PROXY (GET, POST, DELETE)
            new RouteExclusion(null, Pattern.compile(".*/deployments/.*/mcp.*")),   // APPLICATION_MCP_PROXY (GET, POST, DELETE)

            // CONFIG_RESOURCE route: POST returns 405 Method Not Allowed (not a real endpoint)
            new RouteExclusion("POST", Pattern.compile(".*/\\(models\\|.*\\)/.*")),

            // CONFIG_RESOURCE_METADATA route: POST/PUT/DELETE are bulk operations (not CRUD)
            new RouteExclusion("POST", Pattern.compile(".*/metadata/\\(models\\|.*\\)/.*")),
            new RouteExclusion("PUT", Pattern.compile(".*/metadata/\\(models\\|.*\\)/.*")),
            new RouteExclusion("DELETE", Pattern.compile(".*/metadata/\\(models\\|.*\\)/.*")),

            // PLATFORM_APP_TOOLSET_RESOURCE route: POST returns 405 Method Not Allowed (not a real endpoint)
            new RouteExclusion("POST", Pattern.compile(".*/\\(applications\\|toolsets\\)/.*")),

            // PLATFORM_APP_TOOLSET_RESOURCE_METADATA route: POST/PUT/DELETE are bulk operations (not CRUD)
            new RouteExclusion("POST", Pattern.compile(".*/metadata/\\(applications\\|toolsets\\)/.*")),
            new RouteExclusion("PUT", Pattern.compile(".*/metadata/\\(applications\\|toolsets\\)/.*")),
            new RouteExclusion("DELETE", Pattern.compile(".*/metadata/\\(applications\\|toolsets\\)/.*")),

            // FILE_CONFIG route: POST/PUT/DELETE are admin bulk operations
            new RouteExclusion("POST", Pattern.compile(".*/admin/config/file/.*")),
            new RouteExclusion("PUT", Pattern.compile(".*/admin/config/file/.*")),
            new RouteExclusion("DELETE", Pattern.compile(".*/admin/config/file/.*"))
    );

    /**
     * Exclusion rule combining HTTP method and route pattern.
     *
     * @param method HTTP method (e.g., "GET", "POST") or null to match all methods
     *
     * @param pattern Route pattern to match
     */
    private record RouteExclusion(String method, Pattern pattern) {
        boolean matches(String httpMethod, Pattern routePattern) {
            if (method != null && !method.equals(httpMethod)) {
                return false;
            }
            return pattern.matcher(routePattern.pattern()).matches();
        }
    }

    @Test
    void allEndpointsMatchControllerRoutes() throws Exception {
        List<?> routes = getControllerRoutes();
        List<EndpointMetadata.Endpoint> endpoints = AnnotationEndpointCollector.collect();

        assertFalse(endpoints.isEmpty(), "EndpointMetadata should not be empty");
        assertFalse(routes.isEmpty(), "ControllerSelector routes should not be empty");

        for (EndpointMetadata.Endpoint endpoint : endpoints) {
            if (isProxyHandledEndpoint(endpoint)) {
                continue;
            }

            String samplePath = RouteExtractor.toSamplePath(endpoint.path());
            String httpMethod = endpoint.method();
            boolean matched = false;

            for (Object route : routes) {
                String routeMethod = getRouteMethodName(route);
                Pattern routePattern = getRoutePattern(route);

                if (routeMethod.equals(httpMethod) && routePattern.matcher(samplePath).find()) {
                    matched = true;
                    break;
                }
            }

            assertTrue(matched,
                    "EndpointMetadata entry has no matching route in ControllerSelector: "
                            + endpoint.method() + " " + endpoint.path());
        }
    }

    @Test
    void allRoutesHaveEndpointMetadata() throws Exception {
        List<?> routes = getControllerRoutes();
        List<EndpointMetadata.Endpoint> endpoints = AnnotationEndpointCollector.collect();

        List<String> uncoveredRoutes = new ArrayList<>();

        for (Object route : routes) {
            String routeMethod = getRouteMethodName(route);
            Pattern routePattern = getRoutePattern(route);

            // Skip intentionally excluded routes
            if (isExcluded(routeMethod, routePattern)) {
                continue;
            }

            // Check if route is covered by any endpoint metadata
            boolean covered = false;
            for (EndpointMetadata.Endpoint endpoint : endpoints) {
                if (!endpoint.method().equals(routeMethod)) {
                    continue;
                }

                String samplePath = RouteExtractor.toSamplePath(endpoint.path());
                if (routePattern.matcher(samplePath).find()) {
                    covered = true;
                    break;
                }
            }

            if (!covered) {
                String samplePath = patternToSamplePath(routePattern.pattern());
                uncoveredRoutes.add(routeMethod + " " + routePattern.pattern() + " -> " + samplePath);
            }
        }

        assertTrue(uncoveredRoutes.isEmpty(),
                "The following routes lack @ApiOperation annotations:\n  "
                + String.join("\n  ", uncoveredRoutes));
    }

    private static boolean isExcluded(String httpMethod, Pattern routePattern) {
        return EXCLUDED_ROUTES.stream()
                .anyMatch(exclusion -> exclusion.matches(httpMethod, routePattern));
    }

    /**
     * Converts a regex route pattern into a concrete sample path by:
     * - Replacing named groups like {@code (?<bucket>[...])} with sample values
     * - Replacing alternation groups like {@code (models|interceptors|roles)} with first option
     * - Removing other regex operators
     */
    private String patternToSamplePath(String regexPattern) {
        String result = regexPattern;

        // Remove anchors
        result = result.replaceAll("^\\^", "").replaceAll("\\$$", "");

        // Replace named groups like (?<bucket>[a-zA-Z0-9_-]+) with bucket-example
        result = result.replaceAll("\\(\\?<(\\w+)>[^)]+\\)", "$1-example");

        // Replace alternation groups like (models|interceptors|roles) with first option
        // Handle both non-capturing groups (?:...) and regular groups (...)
        result = result.replaceAll("\\(\\?:([^|)]+)(?:\\|[^)]*)?\\)", "$1");
        result = result.replaceAll("\\(([^|)]+)(?:\\|[^)]*)?\\)", "$1");

        // Replace .* and .+ with "path"
        result = result.replaceAll("\\.\\*", "path").replaceAll("\\.\\+", "path");

        // Replace character classes like [a-zA-Z0-9_-]+ with "value"
        result = result.replaceAll("\\[[^]]+\\]\\+", "value");

        // Remove ? quantifiers (optional markers)
        result = result.replaceAll("\\?", "");

        return result;
    }

    @Test
    void noDuplicateOperationIds() {
        List<EndpointMetadata.Endpoint> endpoints = AnnotationEndpointCollector.collect();
        Set<String> operationIds = new HashSet<>();

        for (EndpointMetadata.Endpoint endpoint : endpoints) {
            boolean added = operationIds.add(endpoint.operationId());
            assertTrue(added,
                    "Duplicate operationId found: " + endpoint.operationId());
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

    private List<?> getControllerRoutes() throws Exception {
        Field routesField = ControllerSelector.class.getDeclaredField("ROUTES");
        routesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<?> routes = (List<?>) routesField.get(null);
        return routes;
    }

    private String getRouteMethodName(Object route) throws Exception {
        Method accessor = route.getClass().getDeclaredMethod("method");
        accessor.setAccessible(true);
        Object httpMethod = accessor.invoke(route);
        return httpMethod.toString();
    }

    private Pattern getRoutePattern(Object route) throws Exception {
        Method accessor = route.getClass().getDeclaredMethod("pathPattern");
        accessor.setAccessible(true);
        return (Pattern) accessor.invoke(route);
    }
}