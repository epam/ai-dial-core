package com.epam.aidial.core.openapi;

import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.controller.ControllerSelector;
import com.epam.aidial.core.server.data.RouteTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Validates endpoint metadata against registered routes and OpenAPI requirements.
 */
public final class EndpointValidator {

    private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{([^}]+)}");

    private static final Set<Integer> VALID_HTTP_STATUS_CODES = Set.of(
            // 1xx Informational
            100, 101, 102, 103,
            // 2xx Success
            200, 201, 202, 203, 204, 205, 206, 207, 208, 226,
            // 3xx Redirection
            300, 301, 302, 303, 304, 305, 307, 308,
            // 4xx Client Error
            400, 401, 402, 403, 404, 405, 406, 407, 408, 409,
            410, 411, 412, 413, 414, 415, 416, 417, 418, 421,
            422, 423, 424, 425, 426, 428, 429, 431, 451,
            // 5xx Server Error
            500, 501, 502, 503, 504, 505, 506, 507, 508, 510, 511
    );
    private static final Set<RouteTemplate> EXCLUDE_ROUTE_TEMPLATES = Set.of(
            RouteTemplate.TOOL_SET_PROXY_METADATA, RouteTemplate.APPLICATION_MCP_PROXY_METADATA, RouteTemplate.DEPLOYMENT_ROUTES,
            RouteTemplate.TOOL_SET_MCP_PROXY, RouteTemplate.APPLICATION_MCP_PROXY);

    private static final List<RouteInfo> EXCLUDED_ROUTES = List.of(
        new RouteInfo(null, RouteTemplate.DEPLOYMENT_ROUTES.getPattern()),
        new RouteInfo(null, RouteTemplate.TOOL_SET_PROXY_METADATA.getPattern()),
        new RouteInfo(null, RouteTemplate.APPLICATION_MCP_PROXY_METADATA.getPattern()),

        new RouteInfo(null, RouteTemplate.TOOL_SET_MCP_PROXY.getPattern()),
        new RouteInfo(null, RouteTemplate.APPLICATION_MCP_PROXY.getPattern()),

        new RouteInfo("POST", RouteTemplate.CONFIG_RESOURCE.getPattern()),

        new RouteInfo("POST", RouteTemplate.CONFIG_RESOURCE_METADATA.getPattern()),
        new RouteInfo("PUT", RouteTemplate.CONFIG_RESOURCE_METADATA.getPattern()),
        new RouteInfo("DELETE", RouteTemplate.CONFIG_RESOURCE_METADATA.getPattern()),

        new RouteInfo("POST", RouteTemplate.PLATFORM_APP_TOOLSET_RESOURCE.getPattern()),

        new RouteInfo("POST", RouteTemplate.PLATFORM_APP_TOOLSET_RESOURCE_METADATA.getPattern()),
        new RouteInfo("PUT", RouteTemplate.PLATFORM_APP_TOOLSET_RESOURCE_METADATA.getPattern()),
        new RouteInfo("DELETE", RouteTemplate.PLATFORM_APP_TOOLSET_RESOURCE_METADATA.getPattern()),

        new RouteInfo("POST", RouteTemplate.ADMIN_FILE_CONFIG.getPattern()),
        new RouteInfo("PUT", RouteTemplate.ADMIN_FILE_CONFIG.getPattern()),
        new RouteInfo("DELETE", RouteTemplate.ADMIN_FILE_CONFIG.getPattern())
    );

    private record RouteInfo(String method, Pattern pattern) {
        boolean matches(String httpMethod, Pattern routePattern) {
            return (method == null || method.equals(httpMethod))
                    && pattern().equals(routePattern);
        }
    }

    private EndpointValidator() {
    }

    /**
     * Validates endpoint metadata against registered routes and OpenAPI requirements.
     *
     * @param endpoints List of endpoints to validate
     * @throws IllegalStateException if validation fails
     */
    public static void validate(List<EndpointMetadata.Endpoint> endpoints) {
        validateRequiredFields(endpoints);
        validatePathFormat(endpoints);
        validateUniqueEndpoints(endpoints);
        validatePathParameters(endpoints);
        validateResponseCodes(endpoints);
        validateApiSchemas(endpoints);
        validateUniqueOperationIds(endpoints);
        validatePathsMatchRoutes(endpoints);
        validateControllerRouteCoverage(endpoints);
        validateRouteCoverage(endpoints);
    }

    /**
     * Validates that all required @ApiOperation fields are present and non-empty.
     *
     * @param endpoints List of endpoints to validate
     * @throws IllegalStateException if any required field is missing or empty
     */
    private static void validateRequiredFields(List<EndpointMetadata.Endpoint> endpoints) {
        List<String> errors = new ArrayList<>();

        for (EndpointMetadata.Endpoint endpoint : endpoints) {
            String location = endpoint.method() + " " + endpoint.path();

            if (endpoint.method() == null || endpoint.method().trim().isEmpty()) {
                errors.add("@ApiOperation(" + location + "): method must not be empty");
            }

            if (endpoint.path() == null || endpoint.path().trim().isEmpty()) {
                errors.add("@ApiOperation(" + location + "): path must not be empty");
            }

            if (endpoint.operationId() == null || endpoint.operationId().trim().isEmpty()) {
                errors.add("@ApiOperation(" + location + "): operationId must not be empty");
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "OpenAPI spec generation failed: Required @ApiOperation fields are missing or empty:\n  "
                            + String.join("\n  ", errors)
            );
        }
    }

    /**
     * Validates that each (HTTP method, path) combination is unique.
     *
     * @param endpoints List of endpoints to validate
     * @throws IllegalStateException if duplicate endpoints are found
     */
    private static void validateUniqueEndpoints(List<EndpointMetadata.Endpoint> endpoints) {
        Map<String, List<EndpointMetadata.Endpoint>> endpointMap = new HashMap<>();

        for (EndpointMetadata.Endpoint endpoint : endpoints) {
            String key = endpoint.method() + " " + endpoint.path();
            endpointMap.computeIfAbsent(key, k -> new ArrayList<>()).add(endpoint);
        }

        List<String> duplicates = endpointMap.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> {
                    String endpoint = entry.getKey();
                    String operationIds = entry.getValue().stream()
                            .map(EndpointMetadata.Endpoint::operationId)
                            .collect(Collectors.joining(", "));
                    return "Duplicate endpoint detected: " + endpoint
                            + "\n  Found in operations: " + operationIds;
                })
                .collect(Collectors.toList());

        if (!duplicates.isEmpty()) {
            throw new IllegalStateException(
                    "OpenAPI spec generation failed: Duplicate (method, path) combinations detected:\n\n"
                            + String.join("\n\n", duplicates)
                            + "\n\nEach (method, path) combination must be unique."
            );
        }
    }

    /**
     * Validates that path parameters match between path template and @ApiParameter annotations.
     *
     * @param endpoints List of endpoints to validate
     * @throws IllegalStateException if path parameters don't match
     */
    private static void validatePathParameters(List<EndpointMetadata.Endpoint> endpoints) {
        List<String> errors = new ArrayList<>();

        for (EndpointMetadata.Endpoint endpoint : endpoints) {
            // Extract parameters from path template
            Set<String> pathParams = new HashSet<>();
            Matcher matcher = PATH_PARAM_PATTERN.matcher(endpoint.path());
            while (matcher.find()) {
                pathParams.add(matcher.group(1));
            }

            // Extract PATH parameters from annotations
            Set<String> declaredPathParams = new HashSet<>();
            for (ApiParameter param : endpoint.parameters()) {
                if (param.in() == ParameterIn.PATH) {
                    declaredPathParams.add(param.name());
                }
            }

            // Check for missing declarations
            Set<String> missingDeclarations = new HashSet<>(pathParams);
            missingDeclarations.removeAll(declaredPathParams);
            if (!missingDeclarations.isEmpty()) {
                errors.add(endpoint.method() + " " + endpoint.path()
                        + " is missing PATH parameter(s): " + String.join(", ", missingDeclarations));
            }

            // Check for extra declarations
            Set<String> extraDeclarations = new HashSet<>(declaredPathParams);
            extraDeclarations.removeAll(pathParams);
            if (!extraDeclarations.isEmpty()) {
                errors.add(endpoint.method() + " " + endpoint.path()
                        + " declares PATH parameter(s) not present in path: " + String.join(", ", extraDeclarations));
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "OpenAPI spec generation failed: Path parameter mismatches detected:\n  "
                            + String.join("\n  ", errors)
                            + "\n\nEvery {parameter} in the path must have a corresponding @ApiParameter(in = PATH)."
            );
        }
    }

    /**
     * Validates response codes are valid HTTP status codes and unique per (code, contentType) combination.
     *
     * @param endpoints List of endpoints to validate
     * @throws IllegalStateException if invalid or duplicate response codes are found
     */
    private static void validateResponseCodes(List<EndpointMetadata.Endpoint> endpoints) {
        List<String> errors = new ArrayList<>();

        for (EndpointMetadata.Endpoint endpoint : endpoints) {
            String location = endpoint.operationId() + " (" + endpoint.method() + " " + endpoint.path() + ")";
            Map<Integer, Set<String>> codeToContentTypes = new HashMap<>();

            for (ApiResponse response : endpoint.responses()) {
                int code = response.code();

                // Validate HTTP status code
                if (!VALID_HTTP_STATUS_CODES.contains(code)) {
                    errors.add(location + ": invalid HTTP status code " + code);
                }

                // Check for duplicate (code, contentType) combinations
                Set<String> contentTypes = codeToContentTypes.computeIfAbsent(code, k -> new HashSet<>());
                for (String contentType : response.contentTypes()) {
                    if (!contentTypes.add(contentType)) {
                        errors.add(location + ": duplicate response for code " + code + " and contentType " + contentType);
                    }
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "OpenAPI spec generation failed: Invalid or duplicate response codes detected:\n  "
                            + String.join("\n  ", errors)
                            + "\n\nResponse codes must be valid HTTP status codes, and each (code, contentType) combination must be unique within an operation."
            );
        }
    }

    /**
     * Validates that @ApiSchema definitions use mutually exclusive strategies.
     *
     * @param endpoints List of endpoints to validate
     * @throws IllegalStateException if conflicting schema strategies are found
     */
    private static void validateApiSchemas(List<EndpointMetadata.Endpoint> endpoints) {
        List<String> errors = new ArrayList<>();

        for (EndpointMetadata.Endpoint endpoint : endpoints) {
            String location = endpoint.operationId() + " (" + endpoint.method() + " " + endpoint.path() + ")";

            // Validate request body schema
            if (endpoint.requestBody() != null) {
                validateSingleSchema(endpoint.requestBody(), location + " requestBody", errors);
            }

            // Validate response schemas
            for (ApiResponse response : endpoint.responses()) {
                if (response.body() != null) {
                    validateSingleSchema(response.body(),
                            location + " response " + response.code(), errors);
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "OpenAPI spec generation failed: @ApiSchema validation errors:\n  "
                            + String.join("\n  ", errors)
                            + "\n\n@ApiSchema strategies must be mutually exclusive: use only one of implementation, schemaRef, oneOf, or allOf."
            );
        }
    }

    /**
     * Validates a single @ApiSchema for mutual exclusivity of strategies.
     */
    private static void validateSingleSchema(ApiSchema schema, String location, List<String> errors) {
        int strategyCount = 0;

        if (schema.implementation() != null && schema.implementation() != Void.class) {
            strategyCount++;
        }

        if (schema.schemaRef() != null && !schema.schemaRef().isEmpty()) {
            strategyCount++;
        }

        boolean hasOneOf = (schema.oneOf() != null && schema.oneOf().length > 0)
                || (schema.oneOfSchemaRefs() != null && schema.oneOfSchemaRefs().length > 0);
        if (hasOneOf) {
            strategyCount++;
        }

        boolean hasAllOf = (schema.allOf() != null && schema.allOf().length > 0)
                || (schema.allOfSchemaRefs() != null && schema.allOfSchemaRefs().length > 0);
        if (hasAllOf) {
            strategyCount++;
        }

        if (strategyCount > 1) {
            List<String> strategies = getStrings(schema, hasOneOf, hasAllOf);
            errors.add(location + " uses multiple @ApiSchema strategies: " + String.join(", ", strategies));
        }
    }

    private static List<String> getStrings(ApiSchema schema, boolean hasOneOf, boolean hasAllOf) {
        List<String> strategies = new ArrayList<>();
        if (schema.implementation() != null && schema.implementation() != Void.class) {
            strategies.add("implementation");
        }
        if (schema.schemaRef() != null && !schema.schemaRef().isEmpty()) {
            strategies.add("schemaRef");
        }
        if (hasOneOf) {
            strategies.add("oneOf");
        }
        if (hasAllOf) {
            strategies.add("allOf");
        }
        return strategies;
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
     * Validates that endpoint paths use the standard OpenAPI path template syntax.
     * Supported examples:
     *   /v1/users/{id}
     *   /v1/deployments/{deployment_name}
     * Unsupported examples:
     *   /v1/users/{id:[0-9]+}
     *   /v1/{path:.+}
     *   /v1/users/*
     *   /v1/**
     */
    private static void validatePathFormat(List<EndpointMetadata.Endpoint> endpoints) {
        List<String> errors = new ArrayList<>();

        for (EndpointMetadata.Endpoint endpoint : endpoints) {
            String path = endpoint.path();

            if (path == null || path.isBlank()) {
                continue;
            }

            // Wildcards are not part of the OpenAPI path template syntax
            if (path.contains("*")) {
                errors.add(endpoint.method() + " " + path
                        + ": wildcard path patterns are not supported");
            }

            // Validate parameter names inside {...}
            Matcher matcher = PATH_PARAM_PATTERN.matcher(path);
            while (matcher.find()) {
                String parameter = matcher.group(1);

                if (!parameter.matches("[A-Za-z][A-Za-z0-9_-]*")) {
                    errors.add(endpoint.method() + " " + path
                            + ": invalid path parameter '" + parameter + "'");
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "OpenAPI spec generation failed: Invalid path template syntax:\n  "
                            + String.join("\n  ", errors)
                            + "\n\nOnly standard OpenAPI path templates are supported "
                            + "(for example, /v1/users/{id}). "
                            + "Regular expressions, wildcards, and other custom path patterns are not supported."
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

    private static boolean isExcluded(String httpMethod, Pattern routePattern) {
        return EXCLUDED_ROUTES.stream()
            .anyMatch(exclusion -> exclusion.matches(httpMethod, routePattern));
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

    /**
     * Validates that every simple RouteTemplate has a corresponding @ApiOperation.
     * Route templates containing alternation (|), optional groups, or other
     * complex regular expression constructs are skipped because they may represent
     * multiple OpenAPI operations.
     */
    private static void validateRouteCoverage(List<EndpointMetadata.Endpoint> endpoints) {
        Set<String> documentedEndpoints = endpoints.stream()
                .map(EndpointMetadata.Endpoint::path)
                .map(EndpointValidator::normalizePath)
                .collect(Collectors.toSet());

        List<String> errors = new ArrayList<>();

        for (RouteTemplate route : RouteTemplate.values()) {
            if (!EXCLUDE_ROUTE_TEMPLATES.contains(route)) {
                for (String expected : expandRoute(route)) {
                    String normalizedExpected = normalizePath(expected);

                    if (!documentedEndpoints.contains(normalizedExpected)) {
                        errors.add(expected);
                    }
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "OpenAPI spec generation failed: Missing @ApiOperation annotations for routes:\n  "
                            + String.join("\n  ", errors)
            );
        }
    }

    /**
     * Expands a RouteTemplate into the list of expected OpenAPI paths.
     *
     * <p>Examples:
     *
     * <pre>
     * Regex:
     *   ^/v1/ops/publication/(list|get|create)$
     *
     * Template:
     *   /v1/ops/publication/{operation}
     *
     * Result:
     *   /v1/ops/publication/list
     *   /v1/ops/publication/get
     *   /v1/ops/publication/create
     * </pre>
     *
     * <pre>
     * Regex:
     *   ^/openai/deployments/(?&lt;id&gt;.+?)/(completions|chat/completions|embeddings)$
     *
     * Template:
     *   /openai/deployments/{id}/{action}
     *
     * Result:
     *   /openai/deployments/{id}/completions
     *   /openai/deployments/{id}/chat/completions
     *   /openai/deployments/{id}/embeddings
     * </pre>
     *
     * <p>If the regex does not contain an alternation group, the normalized template
     * itself is returned.
     */

    private static void validateControllerRouteCoverage(List<EndpointMetadata.Endpoint> endpoints) {
        List<RouteInfo> routes = getControllerRoutes();

        List<String> uncoveredRoutes = new ArrayList<>();

        for (RouteInfo route : routes) {
            if (isExcluded(route.method, route.pattern)) {
                continue;
            }

            boolean covered = false;

            for (EndpointMetadata.Endpoint endpoint : endpoints) {
                if (!endpoint.method().equals(route.method())) {
                    continue;
                }

                String samplePath = RouteExtractor.toSamplePath(endpoint.path());

                if (route.pattern().matcher(samplePath).find()) {
                    covered = true;
                    break;
                }
            }

            if (!covered) {
                String samplePath = patternToSamplePath(route.pattern().pattern());
                uncoveredRoutes.add(route.method() + " " + route.pattern().pattern() + " -> " + samplePath);
            }
        }

        if (!uncoveredRoutes.isEmpty()) {
            throw new IllegalStateException(
                    "OpenAPI spec generation failed: The following ControllerSelector routes lack corresponding @ApiOperation annotations:\n  "
                    + String.join("\n  ", uncoveredRoutes)
            );
        }
    }

    private static List<String> expandRoute(RouteTemplate route) {
        String regex = route.getPattern().pattern();
        List<String> paths = new ArrayList<>(List.of(route.getNormalizedPath()));

        Matcher placeholderMatcher = PATH_PARAM_PATTERN.matcher(route.getNormalizedPath());

        int searchFrom = 0;

        while (placeholderMatcher.find()) {
            String placeholder = "{" + placeholderMatcher.group(1) + "}";

            Group group = findNextGroup(regex, placeholder, searchFrom);
            if (group == null) {
                continue;
            }

            if (isExpandableAlternation(group.expression())) {
                paths = expand(paths, placeholder, splitAlternatives(group.expression()));
            }

            searchFrom = group.end() + 1;
        }

        return paths;
    }

    private static Group findNextGroup(String regex, String placeholder, int searchFrom) {

        int namedGroupStart = regex.indexOf(
                "(?<" + placeholder.substring(1, placeholder.length() - 1) + ">", searchFrom);

        if (namedGroupStart >= 0) {
            int bodyStart = regex.indexOf('>', namedGroupStart) + 1;
            int bodyEnd = findClosingParen(regex, bodyStart);

            return new Group(
                regex.substring(bodyStart, bodyEnd),
                bodyEnd
            );
        }

        int unnamedGroupStart = findNextUnnamedAlternation(regex, searchFrom);

        if (unnamedGroupStart < 0) {
            return null;
        }

        int bodyStart = unnamedGroupStart + 1;
        int bodyEnd = findClosingParen(regex, bodyStart);

        return new Group(
            regex.substring(bodyStart, bodyEnd),
            bodyEnd
        );
    }

    private static List<String> expand(List<String> paths,
                                       String placeholder,
                                       List<String> values) {

        List<String> result = new ArrayList<>();

        for (String value : values) {
            for (String path : paths) {
                result.add(path.replace(placeholder, value));
            }
        }

        return result;
    }

    private static int findClosingParen(String regex, int from) {

        int depth = 1;

        for (int i = from; i < regex.length(); i++) {

            char c = regex.charAt(i);

            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }

        throw new IllegalStateException("Unbalanced regex: " + regex);
    }

    private static int findNextUnnamedAlternation(String regex, int from) {
        for (int i = from; i < regex.length(); i++) {
            if (regex.charAt(i) != '('
                    || regex.startsWith("(?<", i)
                    || regex.startsWith("(?:", i)) {
                continue;
            }

            int end = findClosingParen(regex, i + 1);

            if (isExpandableAlternation(regex.substring(i + 1, end))) {
                return i;
            }

            i = end;
        }

        return -1;
    }

    private static List<String> splitAlternatives(String body) {

        List<String> result = new ArrayList<>();

        int depth = 0;
        StringBuilder current = new StringBuilder();

        for (char c : body.toCharArray()) {

            if (c == '(') {
                depth++;
            }
            if (c == ')') {
                depth--;
            }

            if (c == '|' && depth == 0) {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }

        result.add(current.toString());

        return result;
    }

    private static boolean isExpandableAlternation(String expression) {
        if (expression.contains("(?:")
                || expression.contains("(?=")
                || expression.contains("(?!")
                || expression.contains("(?<")
                || expression.contains("[")
                || expression.contains("*")
                || expression.contains("+")
                || expression.contains("?")
                || expression.contains("{")
                || expression.contains("\\")) {
            return false;
        }

        return expression.contains("|");
    }

    private static String normalizePath(String path) {
        return PATH_PARAM_PATTERN.matcher(path).replaceAll("{}");
    }

    private static String patternToSamplePath(String regexPattern) {
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

    private static boolean isRegisteredRoute(String expectedPath, String method) {
        String samplePath = RouteExtractor.toSamplePath(expectedPath);

        return getControllerRoutes().stream()
            .anyMatch(route ->
                route.method().equals(method)
                    && route.pattern().matcher(samplePath).find());
    }

    private record Group(String expression, int end) {
    }
}
