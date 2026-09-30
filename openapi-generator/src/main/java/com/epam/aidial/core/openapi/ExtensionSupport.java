package com.epam.aidial.core.openapi;

import com.epam.aidial.core.openapi.annotations.ApiExtension;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts {@link ApiExtension} annotations into OpenAPI vendor extensions, shared by operations and
 * parameters.
 */
final class ExtensionSupport {

    private ExtensionSupport() {
    }

    /**
     * Validates the extensions (names must start with {@code x-} and be unique) and parses each value:
     * booleans and numbers become typed values, anything else stays a string.
     *
     * @return extension name to parsed value, in declaration order
     */
    static Map<String, Object> parse(ApiExtension[] extensions) {
        Map<String, Object> parsed = new LinkedHashMap<>();
        for (ApiExtension ext : extensions) {
            String name = ext.name();

            // Validate extension name starts with "x-"
            if (!name.startsWith("x-")) {
                throw new IllegalArgumentException(
                    "OpenAPI extension name must start with 'x-': " + name
                );
            }

            // Validate no duplicates
            if (parsed.containsKey(name)) {
                throw new IllegalArgumentException(
                    "Duplicate OpenAPI extension name: " + name
                );
            }

            parsed.put(name, parseValue(ext.value()));
        }
        return parsed;
    }

    private static Object parseValue(String value) {
        // Parse value - try boolean, then number, else keep as string
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e1) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e2) {
                return value;
            }
        }
    }
}
