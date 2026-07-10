package com.epam.aidial.core.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the generated OpenAPI specification has deterministic ordering
 * for all elements as per the requirements.
 */
class OrderingVerificationTest {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    @Test
    void pathsAreSortedAlphabetically() throws Exception {
        String yaml = new SpecAssembler("test-v1").assemble();
        JsonNode root = YAML_MAPPER.readTree(yaml);
        JsonNode paths = root.get("paths");

        List<String> pathList = new ArrayList<>();
        paths.fieldNames().forEachRemaining(pathList::add);

        List<String> sortedPaths = new ArrayList<>(pathList);
        sortedPaths.sort(String::compareTo);

        assertEquals(sortedPaths, pathList, "Paths must be sorted alphabetically");
    }

    @Test
    void httpMethodsAreSortedInStandardOrder() throws Exception {
        String yaml = new SpecAssembler("test-v1").assemble();
        JsonNode root = YAML_MAPPER.readTree(yaml);
        JsonNode paths = root.get("paths");

        List<String> expectedOrder = List.of("get", "post", "put", "patch", "delete", "options", "head", "trace");

        paths.fields().forEachRemaining(pathEntry -> {
            JsonNode pathItem = pathEntry.getValue();
            List<String> actualMethods = new ArrayList<>();
            pathItem.fieldNames().forEachRemaining(fieldName -> {
                if (expectedOrder.contains(fieldName.toLowerCase())) {
                    actualMethods.add(fieldName.toLowerCase());
                }
            });

            if (actualMethods.size() > 1) {
                for (int i = 0; i < actualMethods.size() - 1; i++) {
                    int currentIndex = expectedOrder.indexOf(actualMethods.get(i));
                    int nextIndex = expectedOrder.indexOf(actualMethods.get(i + 1));
                    assertTrue(currentIndex < nextIndex,
                            String.format("HTTP methods for path %s are not in standard order. Found %s before %s",
                                    pathEntry.getKey(), actualMethods.get(i), actualMethods.get(i + 1)));
                }
            }
        });
    }

    @Test
    void responsesAreSortedByStatusCode() throws Exception {
        String yaml = new SpecAssembler("test-v1").assemble();
        JsonNode root = YAML_MAPPER.readTree(yaml);
        JsonNode paths = root.get("paths");

        paths.fields().forEachRemaining(pathEntry -> {
            JsonNode pathItem = pathEntry.getValue();
            pathItem.fields().forEachRemaining(methodEntry -> {
                if (!methodEntry.getKey().equals("parameters")) {
                    JsonNode operation = methodEntry.getValue();
                    if (operation.has("responses")) {
                        JsonNode responses = operation.get("responses");
                        List<String> responseCodes = new ArrayList<>();
                        responses.fieldNames().forEachRemaining(responseCodes::add);

                        verifyResponseOrdering(responseCodes, pathEntry.getKey(), methodEntry.getKey());
                    }
                }
            });
        });
    }

    private void verifyResponseOrdering(List<String> codes, String path, String method) {
        List<Integer> numericCodes = new ArrayList<>();
        List<String> nonNumericCodes = new ArrayList<>();

        for (String code : codes) {
            if ("default".equals(code)) {
                nonNumericCodes.add(code);
            } else {
                try {
                    numericCodes.add(Integer.parseInt(code));
                } catch (NumberFormatException e) {
                    nonNumericCodes.add(code);
                }
            }
        }

        // Verify numeric codes are in ascending order
        for (int i = 0; i < numericCodes.size() - 1; i++) {
            assertTrue(numericCodes.get(i) < numericCodes.get(i + 1),
                    String.format("Response codes for %s %s are not sorted. Found %d after %d",
                            method.toUpperCase(), path, numericCodes.get(i + 1), numericCodes.get(i)));
        }

        // Verify "default" appears after all numeric codes
        if (codes.contains("default")) {
            int defaultIndex = codes.indexOf("default");
            for (int i = defaultIndex + 1; i < codes.size(); i++) {
                try {
                    Integer.parseInt(codes.get(i));
                    throw new AssertionError(String.format(
                            "Numeric response code %s appears after 'default' in %s %s",
                            codes.get(i), method.toUpperCase(), path));
                } catch (NumberFormatException e) {
                    // Expected for non-numeric codes
                }
            }
        }
    }

    @Test
    void componentSchemasAreSortedAlphabetically() throws Exception {
        String yaml = new SpecAssembler("test-v1").assemble();
        JsonNode root = YAML_MAPPER.readTree(yaml);
        JsonNode components = root.get("components");

        if (components != null && components.has("schemas")) {
            JsonNode schemas = components.get("schemas");
            List<String> schemaNames = new ArrayList<>();
            schemas.fieldNames().forEachRemaining(schemaNames::add);

            List<String> sortedNames = new ArrayList<>(schemaNames);
            sortedNames.sort(String::compareTo);

            assertEquals(sortedNames, schemaNames, "Component schemas must be sorted alphabetically");
        }
    }

    @Test
    void tagsAreSortedAlphabetically() throws Exception {
        String yaml = new SpecAssembler("test-v1").assemble();
        JsonNode root = YAML_MAPPER.readTree(yaml);
        JsonNode tags = root.get("tags");

        if (tags != null && tags.isArray()) {
            List<String> tagNames = new ArrayList<>();
            for (JsonNode tag : tags) {
                if (tag.has("name")) {
                    tagNames.add(tag.get("name").asText());
                }
            }

            List<String> sortedTags = new ArrayList<>(tagNames);
            sortedTags.sort(String::compareTo);

            assertEquals(sortedTags, tagNames, "Tags must be sorted alphabetically");
        }
    }

    @Test
    void contentTypesAreSortedAlphabetically() throws Exception {
        String yaml = new SpecAssembler("test-v1").assemble();
        JsonNode root = YAML_MAPPER.readTree(yaml);
        JsonNode paths = root.get("paths");

        paths.fields().forEachRemaining(pathEntry -> {
            JsonNode pathItem = pathEntry.getValue();
            pathItem.fields().forEachRemaining(methodEntry -> {
                if (!methodEntry.getKey().equals("parameters")) {
                    JsonNode operation = methodEntry.getValue();

                    // Check request body content types
                    if (operation.has("requestBody")) {
                        JsonNode requestBody = operation.get("requestBody");
                        if (requestBody.has("content")) {
                            verifyContentTypesOrdering(requestBody.get("content"),
                                    pathEntry.getKey(), methodEntry.getKey(), "requestBody");
                        }
                    }

                    // Check response content types
                    if (operation.has("responses")) {
                        JsonNode responses = operation.get("responses");
                        responses.fields().forEachRemaining(responseEntry -> {
                            JsonNode response = responseEntry.getValue();
                            if (response.has("content")) {
                                verifyContentTypesOrdering(response.get("content"),
                                        pathEntry.getKey(), methodEntry.getKey(),
                                        "response " + responseEntry.getKey());
                            }
                        });
                    }
                }
            });
        });
    }

    private void verifyContentTypesOrdering(JsonNode content, String path, String method, String context) {
        List<String> contentTypes = new ArrayList<>();
        content.fieldNames().forEachRemaining(contentTypes::add);

        if (contentTypes.size() > 1) {
            List<String> sortedContentTypes = new ArrayList<>(contentTypes);
            sortedContentTypes.sort(String::compareTo);

            assertEquals(sortedContentTypes, contentTypes,
                    String.format("Content types in %s for %s %s must be sorted alphabetically",
                            context, method.toUpperCase(), path));
        }
    }
}
