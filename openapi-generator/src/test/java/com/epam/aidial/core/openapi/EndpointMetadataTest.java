package com.epam.aidial.core.openapi;

import com.epam.aidial.core.server.controller.ControllerSelector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class EndpointMetadataTest {

    // DEPLOYMENT_ROUTES is a pass-through for custom application routes.
    // It dynamically routes to application-specific endpoints and does not need static OpenAPI entries.
    private static final Pattern DEPLOYMENT_ROUTES_PATTERN =
            Pattern.compile(".*deployments.*route.*");

    @Test
    void allEndpointsMatchControllerRoutes() throws Exception {
        List<?> routes = getControllerRoutes();
        List<EndpointMetadata.Endpoint> endpoints = AnnotationEndpointCollector.collect();

        assertFalse(endpoints.isEmpty(), "EndpointMetadata should not be empty");
        assertFalse(routes.isEmpty(), "ControllerSelector routes should not be empty");

        // For each endpoint in EndpointMetadata, verify it matches at least one ControllerSelector route
        for (EndpointMetadata.Endpoint endpoint : endpoints) {
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

        // For each ControllerSelector route (excluding DEPLOYMENT_ROUTES),
        // verify at least one EndpointMetadata endpoint matches its pattern
        for (Object route : routes) {
            String routeMethod = getRouteMethodName(route);
            Pattern routePattern = getRoutePattern(route);

            // Skip DEPLOYMENT_ROUTES
            if (DEPLOYMENT_ROUTES_PATTERN.matcher(routePattern.pattern()).matches()) {
                continue;
            }

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
                fail("ControllerSelector route has no matching EndpointMetadata entry: "
                        + routeMethod + " " + routePattern.pattern());
            }
        }
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
