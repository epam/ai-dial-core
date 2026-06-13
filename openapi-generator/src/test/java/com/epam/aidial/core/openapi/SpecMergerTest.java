package com.epam.aidial.core.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecMergerTest {

    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    @Test
    void mergeArraysPreservesManualContent(@TempDir Path tempDir) throws IOException {
        // Test that when both skeleton and manual have arrays of objects with matching "name",
        // the manual description is preserved via mergeObjectArrays
        ObjectNode skeleton = mapper.createObjectNode();
        ObjectNode skeletonInfo = mapper.createObjectNode();
        skeletonInfo.put("title", "Test");
        skeleton.set("info", skeletonInfo);
        // Use a non-skeleton-preferred field for the array test
        ObjectNode skeletonComponents = mapper.createObjectNode();
        ObjectNode skeletonSchemas = mapper.createObjectNode();
        ObjectNode schemaWithArray = mapper.createObjectNode();
        schemaWithArray.put("type", "object");
        ArrayNode skeletonItems = mapper.createArrayNode();
        ObjectNode skItem = mapper.createObjectNode();
        skItem.put("name", "field1");
        skItem.put("type", "string");
        skeletonItems.add(skItem);
        schemaWithArray.set("x-fields", skeletonItems);
        skeletonSchemas.set("MyType", schemaWithArray);
        skeletonComponents.set("schemas", skeletonSchemas);
        skeleton.set("components", skeletonComponents);

        ObjectNode manual = mapper.createObjectNode();
        ObjectNode manualInfo = mapper.createObjectNode();
        manualInfo.put("title", "Test");
        manual.set("info", manualInfo);
        ObjectNode manualComponents = mapper.createObjectNode();
        ObjectNode manualSchemas = mapper.createObjectNode();
        ObjectNode manualSchemaWithArray = mapper.createObjectNode();
        manualSchemaWithArray.put("type", "object");
        ArrayNode manualItems = mapper.createArrayNode();
        ObjectNode manItem = mapper.createObjectNode();
        manItem.put("name", "field1");
        manItem.put("type", "string");
        manItem.put("description", "The first field");
        manualItems.add(manItem);
        manualSchemaWithArray.set("x-fields", manualItems);
        manualSchemas.set("MyType", manualSchemaWithArray);
        manualComponents.set("schemas", manualSchemas);
        manual.set("components", manualComponents);

        Path skeletonFile = tempDir.resolve("skeleton.yaml");
        Path manualFile = tempDir.resolve("manual.yaml");
        Path outputFile = tempDir.resolve("output.yaml");

        mapper.writeValue(skeletonFile.toFile(), skeleton);
        mapper.writeValue(manualFile.toFile(), manual);

        SpecMerger merger = new SpecMerger();
        merger.merge(skeletonFile, manualFile, outputFile);

        String output = Files.readString(outputFile);
        assertTrue(output.contains("description"), "Merged output should preserve manual descriptions");
        assertTrue(output.contains("The first field"), "Manual description content should be preserved");
    }

    @Test
    void mergeArraysUnionsSimpleArrays(@TempDir Path tempDir) throws IOException {
        ObjectNode skeleton = mapper.createObjectNode();
        ObjectNode skeletonInfo = mapper.createObjectNode();
        skeletonInfo.put("title", "Test");
        skeleton.set("info", skeletonInfo);

        ObjectNode manual = mapper.createObjectNode();
        ObjectNode manualInfo = mapper.createObjectNode();
        manualInfo.put("title", "Test");
        manual.set("info", manualInfo);

        Path skeletonFile = tempDir.resolve("skeleton.yaml");
        Path manualFile = tempDir.resolve("manual.yaml");
        Path outputFile = tempDir.resolve("output.yaml");

        mapper.writeValue(skeletonFile.toFile(), skeleton);
        mapper.writeValue(manualFile.toFile(), manual);

        SpecMerger merger = new SpecMerger();
        merger.merge(skeletonFile, manualFile, outputFile);

        assertTrue(Files.exists(outputFile), "Output file should be created");
    }

    @Test
    void mergedCountOnlyCountsMeaningfulUnits(@TempDir Path tempDir) throws IOException {
        ObjectNode skeleton = mapper.createObjectNode();
        ObjectNode skeletonPaths = mapper.createObjectNode();
        ObjectNode path1 = mapper.createObjectNode();
        path1.put("summary", "Test");
        skeletonPaths.set("/v1/test", path1);
        skeleton.set("paths", skeletonPaths);
        skeleton.put("openapi", "3.0.0");

        ObjectNode manual = mapper.createObjectNode();
        ObjectNode manualPaths = mapper.createObjectNode();
        ObjectNode manualPath1 = mapper.createObjectNode();
        manualPath1.put("summary", "Manual Test");
        manualPaths.set("/v1/test", manualPath1);
        manual.set("paths", manualPaths);
        manual.put("openapi", "3.0.0");

        Path skeletonFile = tempDir.resolve("skeleton.yaml");
        Path manualFile = tempDir.resolve("manual.yaml");
        Path outputFile = tempDir.resolve("output.yaml");

        mapper.writeValue(skeletonFile.toFile(), skeleton);
        mapper.writeValue(manualFile.toFile(), manual);

        SpecMerger merger = new SpecMerger();
        merger.merge(skeletonFile, manualFile, outputFile);

        assertTrue(Files.exists(outputFile), "Output file should be created");
    }

    @Test
    void orphanedEntriesPreserved(@TempDir Path tempDir) throws IOException {
        ObjectNode skeleton = mapper.createObjectNode();
        ObjectNode skeletonPaths = mapper.createObjectNode();
        ObjectNode existingPath = mapper.createObjectNode();
        existingPath.put("summary", "Existing");
        skeletonPaths.set("/v1/existing", existingPath);
        skeleton.set("paths", skeletonPaths);

        ObjectNode manual = mapper.createObjectNode();
        ObjectNode manualPaths = mapper.createObjectNode();
        ObjectNode existingPathManual = mapper.createObjectNode();
        existingPathManual.put("summary", "Existing");
        manualPaths.set("/v1/existing", existingPathManual);
        ObjectNode orphanedPath = mapper.createObjectNode();
        orphanedPath.put("summary", "Legacy endpoint");
        manualPaths.set("/v1/legacy", orphanedPath);
        manual.set("paths", manualPaths);

        Path skeletonFile = tempDir.resolve("skeleton.yaml");
        Path manualFile = tempDir.resolve("manual.yaml");
        Path outputFile = tempDir.resolve("output.yaml");

        mapper.writeValue(skeletonFile.toFile(), skeleton);
        mapper.writeValue(manualFile.toFile(), manual);

        SpecMerger merger = new SpecMerger();
        merger.merge(skeletonFile, manualFile, outputFile);

        String output = Files.readString(outputFile);
        assertTrue(output.contains("/v1/legacy"), "Should preserve orphaned paths");
        assertFalse(output.contains("x-orphaned"), "x-orphaned markers should be stripped from output");
    }

    @Test
    void nestedObjectFieldsUnderPathsNotFalselyOrphaned(@TempDir Path tempDir) throws IOException {
        // Skeleton has an operation with no requestBody; manual has rich requestBody.
        // After fix, the manual's requestBody should NOT get x-orphaned.
        ObjectNode skeleton = mapper.createObjectNode();
        ObjectNode skeletonPaths = mapper.createObjectNode();
        ObjectNode skeletonOp = mapper.createObjectNode();
        ObjectNode skeletonPost = mapper.createObjectNode();
        skeletonPost.put("operationId", "chatCompletions");
        skeletonOp.set("post", skeletonPost);
        skeletonPaths.set("/v1/chat/completions", skeletonOp);
        skeleton.set("paths", skeletonPaths);

        ObjectNode manual = mapper.createObjectNode();
        ObjectNode manualPaths = mapper.createObjectNode();
        ObjectNode manualOp = mapper.createObjectNode();
        ObjectNode manualPost = mapper.createObjectNode();
        manualPost.put("operationId", "chatCompletions");
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("required", true);
        ObjectNode content = mapper.createObjectNode();
        ObjectNode jsonMedia = mapper.createObjectNode();
        jsonMedia.put("description", "Rich request schema");
        content.set("application/json", jsonMedia);
        requestBody.set("content", content);
        manualPost.set("requestBody", requestBody);
        manualOp.set("post", manualPost);
        manualPaths.set("/v1/chat/completions", manualOp);
        manual.set("paths", manualPaths);

        Path skeletonFile = tempDir.resolve("skeleton.yaml");
        Path manualFile = tempDir.resolve("manual.yaml");
        Path outputFile = tempDir.resolve("output.yaml");

        mapper.writeValue(skeletonFile.toFile(), skeleton);
        mapper.writeValue(manualFile.toFile(), manual);

        SpecMerger merger = new SpecMerger();
        merger.merge(skeletonFile, manualFile, outputFile);

        String output = Files.readString(outputFile);
        assertTrue(output.contains("requestBody"), "Manual requestBody should be preserved");
        assertFalse(output.contains("x-orphaned"), "Nested fields should NOT be marked x-orphaned");
    }

    @Test
    void operationLevelTagsPreferSkeleton(@TempDir Path tempDir) throws IOException {
        ObjectNode skeleton = mapper.createObjectNode();
        ObjectNode skeletonPaths = mapper.createObjectNode();
        ObjectNode skeletonPathItem = mapper.createObjectNode();
        ObjectNode skeletonGet = mapper.createObjectNode();
        skeletonGet.put("operationId", "getDeployment");
        ArrayNode skeletonTags = mapper.createArrayNode();
        skeletonTags.add("Deployment listing");
        skeletonGet.set("tags", skeletonTags);
        skeletonPathItem.set("get", skeletonGet);
        skeletonPaths.set("/v1/deployments", skeletonPathItem);
        skeleton.set("paths", skeletonPaths);

        ObjectNode manual = mapper.createObjectNode();
        ObjectNode manualPaths = mapper.createObjectNode();
        ObjectNode manualPathItem = mapper.createObjectNode();
        ObjectNode manualGet = mapper.createObjectNode();
        manualGet.put("operationId", "getDeployment");
        ArrayNode manualTags = mapper.createArrayNode();
        manualTags.add("Old Tag Name");
        manualGet.set("tags", manualTags);
        manualPathItem.set("get", manualGet);
        manualPaths.set("/v1/deployments", manualPathItem);
        manual.set("paths", manualPaths);

        Path skeletonFile = tempDir.resolve("skeleton.yaml");
        Path manualFile = tempDir.resolve("manual.yaml");
        Path outputFile = tempDir.resolve("output.yaml");

        mapper.writeValue(skeletonFile.toFile(), skeleton);
        mapper.writeValue(manualFile.toFile(), manual);

        SpecMerger merger = new SpecMerger();
        merger.merge(skeletonFile, manualFile, outputFile);

        String output = Files.readString(outputFile);
        assertTrue(output.contains("Deployment listing"), "Skeleton tag should be used");
        assertFalse(output.contains("Old Tag Name"), "Manual tag should NOT be in output");
    }

    @Test
    void manualRequestBodyPreservedWhenSkeletonHasNone(@TempDir Path tempDir) throws IOException {
        ObjectNode skeleton = mapper.createObjectNode();
        ObjectNode skeletonPaths = mapper.createObjectNode();
        ObjectNode skeletonPathItem = mapper.createObjectNode();
        ObjectNode skeletonPost = mapper.createObjectNode();
        skeletonPost.put("operationId", "createChat");
        skeletonPathItem.set("post", skeletonPost);
        skeletonPaths.set("/v1/chat", skeletonPathItem);
        skeleton.set("paths", skeletonPaths);

        ObjectNode manual = mapper.createObjectNode();
        ObjectNode manualPaths = mapper.createObjectNode();
        ObjectNode manualPathItem = mapper.createObjectNode();
        ObjectNode manualPost = mapper.createObjectNode();
        manualPost.put("operationId", "createChat");
        ObjectNode reqBody = mapper.createObjectNode();
        reqBody.put("required", true);
        ObjectNode reqContent = mapper.createObjectNode();
        ObjectNode jsonType = mapper.createObjectNode();
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.put("description", "Chat request body");
        jsonType.set("schema", schema);
        reqContent.set("application/json", jsonType);
        reqBody.set("content", reqContent);
        manualPost.set("requestBody", reqBody);
        manualPathItem.set("post", manualPost);
        manualPaths.set("/v1/chat", manualPathItem);
        manual.set("paths", manualPaths);

        Path skeletonFile = tempDir.resolve("skeleton.yaml");
        Path manualFile = tempDir.resolve("manual.yaml");
        Path outputFile = tempDir.resolve("output.yaml");

        mapper.writeValue(skeletonFile.toFile(), skeleton);
        mapper.writeValue(manualFile.toFile(), manual);

        SpecMerger merger = new SpecMerger();
        merger.merge(skeletonFile, manualFile, outputFile);

        String output = Files.readString(outputFile);
        assertTrue(output.contains("requestBody"), "Manual requestBody should be preserved");
        assertTrue(output.contains("Chat request body"), "Request body content should be preserved");
        assertFalse(output.contains("x-orphaned"), "requestBody should NOT be marked x-orphaned");
    }

    @Test
    void response401ContentNotOrphaned(@TempDir Path tempDir) throws IOException {
        ObjectNode skeleton = mapper.createObjectNode();
        ObjectNode skeletonPaths = mapper.createObjectNode();
        ObjectNode skeletonPathItem = mapper.createObjectNode();
        ObjectNode skeletonGet = mapper.createObjectNode();
        skeletonGet.put("operationId", "getResource");
        ObjectNode skeletonResponses = mapper.createObjectNode();
        ObjectNode skeleton401 = mapper.createObjectNode();
        skeleton401.put("description", "Unauthorized");
        skeletonResponses.set("401", skeleton401);
        skeletonGet.set("responses", skeletonResponses);
        skeletonPathItem.set("get", skeletonGet);
        skeletonPaths.set("/v1/resources", skeletonPathItem);
        skeleton.set("paths", skeletonPaths);

        ObjectNode manual = mapper.createObjectNode();
        ObjectNode manualPaths = mapper.createObjectNode();
        ObjectNode manualPathItem = mapper.createObjectNode();
        ObjectNode manualGet = mapper.createObjectNode();
        manualGet.put("operationId", "getResource");
        ObjectNode manualResponses = mapper.createObjectNode();
        ObjectNode manual401 = mapper.createObjectNode();
        manual401.put("description", "Unauthorized");
        ObjectNode errorContent = mapper.createObjectNode();
        ObjectNode errorJson = mapper.createObjectNode();
        errorJson.put("description", "Error schema");
        errorContent.set("application/json", errorJson);
        manual401.set("content", errorContent);
        manualResponses.set("401", manual401);
        manualGet.set("responses", manualResponses);
        manualPathItem.set("get", manualGet);
        manualPaths.set("/v1/resources", manualPathItem);
        manual.set("paths", manualPaths);

        Path skeletonFile = tempDir.resolve("skeleton.yaml");
        Path manualFile = tempDir.resolve("manual.yaml");
        Path outputFile = tempDir.resolve("output.yaml");

        mapper.writeValue(skeletonFile.toFile(), skeleton);
        mapper.writeValue(manualFile.toFile(), manual);

        SpecMerger merger = new SpecMerger();
        merger.merge(skeletonFile, manualFile, outputFile);

        String output = Files.readString(outputFile);
        assertTrue(output.contains("Error schema"), "401 error content should be preserved");
        assertFalse(output.contains("x-orphaned"), "401 content should NOT be marked x-orphaned");
    }

    @Test
    void markersStrippedFromOutput(@TempDir Path tempDir) throws IOException {
        ObjectNode skeleton = mapper.createObjectNode();
        ObjectNode skeletonPaths = mapper.createObjectNode();
        ObjectNode existingPath = mapper.createObjectNode();
        existingPath.put("summary", "Existing");
        skeletonPaths.set("/v1/existing", existingPath);
        ObjectNode newPath = mapper.createObjectNode();
        newPath.put("summary", "New endpoint");
        skeletonPaths.set("/v1/new", newPath);
        skeleton.set("paths", skeletonPaths);

        ObjectNode manual = mapper.createObjectNode();
        ObjectNode manualPaths = mapper.createObjectNode();
        ObjectNode existingPathManual = mapper.createObjectNode();
        existingPathManual.put("summary", "Existing");
        manualPaths.set("/v1/existing", existingPathManual);
        ObjectNode orphanedPath = mapper.createObjectNode();
        orphanedPath.put("summary", "Legacy endpoint");
        manualPaths.set("/v1/legacy", orphanedPath);
        manual.set("paths", manualPaths);

        Path skeletonFile = tempDir.resolve("skeleton.yaml");
        Path manualFile = tempDir.resolve("manual.yaml");
        Path outputFile = tempDir.resolve("output.yaml");

        mapper.writeValue(skeletonFile.toFile(), skeleton);
        mapper.writeValue(manualFile.toFile(), manual);

        SpecMerger merger = new SpecMerger();
        merger.merge(skeletonFile, manualFile, outputFile);

        String output = Files.readString(outputFile);
        assertTrue(output.contains("/v1/legacy"), "Orphaned paths should be preserved");
        assertTrue(output.contains("/v1/new"), "New skeleton paths should be preserved");
        assertFalse(output.contains("x-orphaned"), "x-orphaned markers should be stripped from output");
        assertFalse(output.contains("x-generated"), "x-generated markers should be stripped from output");
    }

    @Test
    void parametersFromBothSourcesMerged(@TempDir Path tempDir) throws IOException {
        ObjectNode skeleton = mapper.createObjectNode();
        ObjectNode skeletonPaths = mapper.createObjectNode();
        ObjectNode skeletonPathItem = mapper.createObjectNode();
        ObjectNode skeletonGet = mapper.createObjectNode();
        skeletonGet.put("operationId", "listItems");
        ArrayNode skeletonParams = mapper.createArrayNode();
        ObjectNode pathParam = mapper.createObjectNode();
        pathParam.put("name", "bucket");
        pathParam.put("in", "path");
        pathParam.put("required", true);
        skeletonParams.add(pathParam);
        skeletonGet.set("parameters", skeletonParams);
        skeletonPathItem.set("get", skeletonGet);
        skeletonPaths.set("/v1/items/{bucket}", skeletonPathItem);
        skeleton.set("paths", skeletonPaths);

        ObjectNode manual = mapper.createObjectNode();
        ObjectNode manualPaths = mapper.createObjectNode();
        ObjectNode manualPathItem = mapper.createObjectNode();
        ObjectNode manualGet = mapper.createObjectNode();
        manualGet.put("operationId", "listItems");
        ArrayNode manualParams = mapper.createArrayNode();
        ObjectNode queryParam = mapper.createObjectNode();
        queryParam.put("name", "api-version");
        queryParam.put("in", "query");
        queryParam.put("required", false);
        manualParams.add(queryParam);
        manualGet.set("parameters", manualParams);
        manualPathItem.set("get", manualGet);
        manualPaths.set("/v1/items/{bucket}", manualPathItem);
        manual.set("paths", manualPaths);

        Path skeletonFile = tempDir.resolve("skeleton.yaml");
        Path manualFile = tempDir.resolve("manual.yaml");
        Path outputFile = tempDir.resolve("output.yaml");

        mapper.writeValue(skeletonFile.toFile(), skeleton);
        mapper.writeValue(manualFile.toFile(), manual);

        SpecMerger merger = new SpecMerger();
        merger.merge(skeletonFile, manualFile, outputFile);

        String output = Files.readString(outputFile);
        assertTrue(output.contains("bucket"), "Skeleton path param should be preserved");
        assertTrue(output.contains("api-version"), "Manual query param should be preserved");
    }

    @Test
    void manualRequestBodyPreferredOverSkeleton(@TempDir Path tempDir) throws IOException {
        ObjectNode skeleton = mapper.createObjectNode();
        ObjectNode skeletonPaths = mapper.createObjectNode();
        ObjectNode skeletonPathItem = mapper.createObjectNode();
        ObjectNode skeletonPost = mapper.createObjectNode();
        skeletonPost.put("operationId", "createChat");
        ObjectNode skeletonReqBody = mapper.createObjectNode();
        skeletonReqBody.put("required", true);
        ObjectNode skeletonContent = mapper.createObjectNode();
        ObjectNode skeletonJson = mapper.createObjectNode();
        ObjectNode skeletonSchema = mapper.createObjectNode();
        skeletonSchema.put("$ref", "#/components/schemas/ChatRequest");
        skeletonJson.set("schema", skeletonSchema);
        skeletonContent.set("application/json", skeletonJson);
        skeletonReqBody.set("content", skeletonContent);
        skeletonPost.set("requestBody", skeletonReqBody);
        skeletonPathItem.set("post", skeletonPost);
        skeletonPaths.set("/v1/chat", skeletonPathItem);
        skeleton.set("paths", skeletonPaths);

        ObjectNode manual = mapper.createObjectNode();
        ObjectNode manualPaths = mapper.createObjectNode();
        ObjectNode manualPathItem = mapper.createObjectNode();
        ObjectNode manualPost = mapper.createObjectNode();
        manualPost.put("operationId", "createChat");
        ObjectNode manualReqBody = mapper.createObjectNode();
        manualReqBody.put("required", true);
        ObjectNode manualContent = mapper.createObjectNode();
        ObjectNode manualJson = mapper.createObjectNode();
        ObjectNode manualSchema = mapper.createObjectNode();
        manualSchema.put("type", "object");
        manualSchema.put("description", "Rich manually-crafted schema with examples");
        manualJson.set("schema", manualSchema);
        manualContent.set("application/json", manualJson);
        manualReqBody.set("content", manualContent);
        manualPost.set("requestBody", manualReqBody);
        manualPathItem.set("post", manualPost);
        manualPaths.set("/v1/chat", manualPathItem);
        manual.set("paths", manualPaths);

        Path skeletonFile = tempDir.resolve("skeleton.yaml");
        Path manualFile = tempDir.resolve("manual.yaml");
        Path outputFile = tempDir.resolve("output.yaml");

        mapper.writeValue(skeletonFile.toFile(), skeleton);
        mapper.writeValue(manualFile.toFile(), manual);

        SpecMerger merger = new SpecMerger();
        merger.merge(skeletonFile, manualFile, outputFile);

        String output = Files.readString(outputFile);
        assertTrue(output.contains("ChatRequest"),
                "Skeleton $ref requestBody should be preserved as source of truth");
        assertTrue(output.contains("Rich manually-crafted schema with examples"),
                "Manual description should be overlaid on skeleton schema");
    }

    @Test
    void sameNameParamsInDifferentLocationsNotMerged(@TempDir Path tempDir) throws IOException {
        ObjectNode skeleton = mapper.createObjectNode();
        ObjectNode skeletonPaths = mapper.createObjectNode();
        ObjectNode skeletonPathItem = mapper.createObjectNode();
        ObjectNode skeletonGet = mapper.createObjectNode();
        skeletonGet.put("operationId", "getItem");
        ArrayNode skeletonParams = mapper.createArrayNode();
        ObjectNode pathParam = mapper.createObjectNode();
        pathParam.put("name", "id");
        pathParam.put("in", "path");
        pathParam.put("required", true);
        skeletonParams.add(pathParam);
        skeletonGet.set("parameters", skeletonParams);
        skeletonPathItem.set("get", skeletonGet);
        skeletonPaths.set("/v1/items/{id}", skeletonPathItem);
        skeleton.set("paths", skeletonPaths);

        ObjectNode manual = mapper.createObjectNode();
        ObjectNode manualPaths = mapper.createObjectNode();
        ObjectNode manualPathItem = mapper.createObjectNode();
        ObjectNode manualGet = mapper.createObjectNode();
        manualGet.put("operationId", "getItem");
        ArrayNode manualParams = mapper.createArrayNode();
        ObjectNode queryParam = mapper.createObjectNode();
        queryParam.put("name", "id");
        queryParam.put("in", "query");
        queryParam.put("description", "Optional filter ID");
        manualParams.add(queryParam);
        manualGet.set("parameters", manualParams);
        manualPathItem.set("get", manualGet);
        manualPaths.set("/v1/items/{id}", manualPathItem);
        manual.set("paths", manualPaths);

        Path skeletonFile = tempDir.resolve("skeleton.yaml");
        Path manualFile = tempDir.resolve("manual.yaml");
        Path outputFile = tempDir.resolve("output.yaml");

        mapper.writeValue(skeletonFile.toFile(), skeleton);
        mapper.writeValue(manualFile.toFile(), manual);

        SpecMerger merger = new SpecMerger();
        merger.merge(skeletonFile, manualFile, outputFile);

        String output = Files.readString(outputFile);
        assertTrue(output.contains("path"), "Path param should be present");
        assertTrue(output.contains("query"), "Query param should be present");
        assertTrue(output.contains("Optional filter ID"),
                "Manual query param description should be preserved (not merged into path param)");
    }

    @Test
    void responsesPreferManual(@TempDir Path tempDir) throws IOException {
        ObjectNode skeleton = mapper.createObjectNode();
        ObjectNode skeletonPaths = mapper.createObjectNode();
        ObjectNode skeletonPathItem = mapper.createObjectNode();
        ObjectNode skeletonGet = mapper.createObjectNode();
        skeletonGet.put("operationId", "getResource");
        ObjectNode skeletonResponses = mapper.createObjectNode();
        ObjectNode skeleton200 = mapper.createObjectNode();
        skeleton200.put("description", "Successful response");
        skeletonResponses.set("200", skeleton200);
        skeletonGet.set("responses", skeletonResponses);
        skeletonPathItem.set("get", skeletonGet);
        skeletonPaths.set("/v1/resource", skeletonPathItem);
        skeleton.set("paths", skeletonPaths);

        ObjectNode manual = mapper.createObjectNode();
        ObjectNode manualPaths = mapper.createObjectNode();
        ObjectNode manualPathItem = mapper.createObjectNode();
        ObjectNode manualGet = mapper.createObjectNode();
        manualGet.put("operationId", "getResource");
        ObjectNode manualResponses = mapper.createObjectNode();
        ObjectNode manual200 = mapper.createObjectNode();
        manual200.put("description", "Returns the resource with examples");
        ObjectNode content200 = mapper.createObjectNode();
        ObjectNode jsonType = mapper.createObjectNode();
        jsonType.put("description", "Detailed example");
        content200.set("application/json", jsonType);
        manual200.set("content", content200);
        manualResponses.set("200", manual200);
        manualGet.set("responses", manualResponses);
        manualPathItem.set("get", manualGet);
        manualPaths.set("/v1/resource", manualPathItem);
        manual.set("paths", manualPaths);

        Path skeletonFile = tempDir.resolve("skeleton.yaml");
        Path manualFile = tempDir.resolve("manual.yaml");
        Path outputFile = tempDir.resolve("output.yaml");

        mapper.writeValue(skeletonFile.toFile(), skeleton);
        mapper.writeValue(manualFile.toFile(), manual);

        SpecMerger merger = new SpecMerger();
        merger.merge(skeletonFile, manualFile, outputFile);

        String output = Files.readString(outputFile);
        assertTrue(output.contains("Returns the resource with examples"), "Manual response description should be preferred");
        assertTrue(output.contains("Detailed example"), "Manual response content should be preserved");
        assertFalse(output.contains("Successful response"), "Skeleton generic response should NOT override manual");
    }

    @Test
    void titlePreferredFromManual(@TempDir Path tempDir) throws IOException {
        ObjectNode skeleton = mapper.createObjectNode();
        ObjectNode skeletonInfo = mapper.createObjectNode();
        skeletonInfo.put("title", "AI DIAL Core API");
        skeletonInfo.put("version", "1.0");
        skeleton.set("info", skeletonInfo);

        ObjectNode manual = mapper.createObjectNode();
        ObjectNode manualInfo = mapper.createObjectNode();
        manualInfo.put("title", "DIAL Core API");
        manualInfo.put("version", "0.9");
        manual.set("info", manualInfo);

        Path skeletonFile = tempDir.resolve("skeleton.yaml");
        Path manualFile = tempDir.resolve("manual.yaml");
        Path outputFile = tempDir.resolve("output.yaml");

        mapper.writeValue(skeletonFile.toFile(), skeleton);
        mapper.writeValue(manualFile.toFile(), manual);

        SpecMerger merger = new SpecMerger();
        merger.merge(skeletonFile, manualFile, outputFile);

        String output = Files.readString(outputFile);
        assertTrue(output.contains("DIAL Core API"), "Manual title should be preferred");
        assertFalse(output.contains("AI DIAL Core API"), "Skeleton title should NOT override manual");
    }

    @Test
    void pathsWithDifferentParamCasingAreMerged(@TempDir Path tempDir) throws IOException {
        ObjectNode skeleton = mapper.createObjectNode();
        ObjectNode skeletonPaths = mapper.createObjectNode();
        ObjectNode skeletonPathItem = mapper.createObjectNode();
        ObjectNode skeletonGet = mapper.createObjectNode();
        skeletonGet.put("operationId", "getApplicationResource");
        ArrayNode skeletonParams = mapper.createArrayNode();
        ObjectNode skeletonPathParam = mapper.createObjectNode();
        skeletonPathParam.put("name", "bucket");
        skeletonPathParam.put("in", "path");
        skeletonPathParam.put("required", true);
        skeletonParams.add(skeletonPathParam);
        skeletonGet.set("parameters", skeletonParams);
        skeletonPathItem.set("get", skeletonGet);
        skeletonPaths.set("/v1/applications/{bucket}/{application_path}", skeletonPathItem);
        skeleton.set("paths", skeletonPaths);

        ObjectNode manual = mapper.createObjectNode();
        ObjectNode manualPaths = mapper.createObjectNode();
        ObjectNode manualPathItem = mapper.createObjectNode();
        ObjectNode manualGet = mapper.createObjectNode();
        manualGet.put("operationId", "getApplicationResource");
        manualGet.put("summary", "Manual summary for application resource");
        ArrayNode manualParams = mapper.createArrayNode();
        ObjectNode manualPathParam = mapper.createObjectNode();
        manualPathParam.put("name", "Bucket");
        manualPathParam.put("in", "path");
        manualPathParam.put("required", true);
        manualPathParam.put("description", "Encrypted bucket name");
        manualParams.add(manualPathParam);
        manualGet.set("parameters", manualParams);
        manualPathItem.set("get", manualGet);
        manualPaths.set("/v1/applications/{Bucket}/{application_path}", manualPathItem);
        manual.set("paths", manualPaths);

        Path skeletonFile = tempDir.resolve("skeleton.yaml");
        Path manualFile = tempDir.resolve("manual.yaml");
        Path outputFile = tempDir.resolve("output.yaml");

        mapper.writeValue(skeletonFile.toFile(), skeleton);
        mapper.writeValue(manualFile.toFile(), manual);

        SpecMerger merger = new SpecMerger();
        merger.merge(skeletonFile, manualFile, outputFile);

        String output = Files.readString(outputFile);
        assertFalse(output.contains("{Bucket}"), "Should not duplicate path with manual casing");
        assertTrue(output.contains("/v1/applications/{bucket}/{application_path}"),
                "Merged output should use skeleton path key");
        assertTrue(output.contains("Manual summary for application resource"),
                "Manual operation fields should be merged");
        assertTrue(output.contains("Encrypted bucket name"),
                "Path parameters with different name casing should merge");
        assertEquals(1, output.split("getApplicationResource", -1).length - 1,
                "Operation should appear once, not as duplicate paths");
    }

    @Test
    void metadataPathsWithDifferentParamCasingAreMerged(@TempDir Path tempDir) throws IOException {
        ObjectNode skeleton = mapper.createObjectNode();
        ObjectNode skeletonPaths = mapper.createObjectNode();
        ObjectNode skeletonPathItem = mapper.createObjectNode();
        ObjectNode skeletonGet = mapper.createObjectNode();
        skeletonGet.put("operationId", "getFileMetadata");
        skeletonPathItem.set("get", skeletonGet);
        skeletonPaths.set("/v1/metadata/files/{bucket}/{path}", skeletonPathItem);
        skeleton.set("paths", skeletonPaths);

        ObjectNode manual = mapper.createObjectNode();
        ObjectNode manualPaths = mapper.createObjectNode();
        ObjectNode manualPathItem = mapper.createObjectNode();
        ObjectNode manualGet = mapper.createObjectNode();
        manualGet.put("operationId", "getFileMetadata");
        manualGet.put("description", "Manual metadata description");
        manualPathItem.set("get", manualGet);
        manualPaths.set("/v1/metadata/files/{Bucket}/{Path}", manualPathItem);
        manual.set("paths", manualPaths);

        Path skeletonFile = tempDir.resolve("skeleton.yaml");
        Path manualFile = tempDir.resolve("manual.yaml");
        Path outputFile = tempDir.resolve("output.yaml");

        mapper.writeValue(skeletonFile.toFile(), skeleton);
        mapper.writeValue(manualFile.toFile(), manual);

        SpecMerger merger = new SpecMerger();
        merger.merge(skeletonFile, manualFile, outputFile);

        String output = Files.readString(outputFile);
        assertFalse(output.contains("{Bucket}"));
        assertFalse(output.contains("{Path}"));
        assertTrue(output.contains("/v1/metadata/files/{bucket}/{path}"));
        assertTrue(output.contains("Manual metadata description"));
    }

    @Test
    void componentSchemaDescriptionPreservedFromManual(@TempDir Path tempDir) throws IOException {
        ObjectNode skeleton = mapper.createObjectNode();
        ObjectNode skeletonComponents = mapper.createObjectNode();
        ObjectNode skeletonSchemas = mapper.createObjectNode();
        ObjectNode skeletonSchema = mapper.createObjectNode();
        skeletonSchema.put("type", "object");
        ArrayNode skeletonRequired = mapper.createArrayNode();
        skeletonRequired.add("model");
        skeletonSchema.set("required", skeletonRequired);
        ObjectNode skeletonProperties = mapper.createObjectNode();
        ObjectNode modelProp = mapper.createObjectNode();
        modelProp.put("type", "string");
        skeletonProperties.set("model", modelProp);
        skeletonSchema.set("properties", skeletonProperties);
        skeletonSchema.put("description", "Auto-generated from Java");
        skeletonSchemas.set("CreateChatCompletionRequest", skeletonSchema);
        skeletonComponents.set("schemas", skeletonSchemas);
        skeleton.set("components", skeletonComponents);

        ObjectNode manual = mapper.createObjectNode();
        ObjectNode manualComponents = mapper.createObjectNode();
        ObjectNode manualSchemas = mapper.createObjectNode();
        ObjectNode manualSchema = mapper.createObjectNode();
        manualSchema.put("description", "Hand-maintained request schema documentation");
        manualSchemas.set("CreateChatCompletionRequest", manualSchema);
        manualComponents.set("schemas", manualSchemas);
        manual.set("components", manualComponents);

        Path skeletonFile = tempDir.resolve("skeleton.yaml");
        Path manualFile = tempDir.resolve("manual.yaml");
        Path outputFile = tempDir.resolve("output.yaml");

        mapper.writeValue(skeletonFile.toFile(), skeleton);
        mapper.writeValue(manualFile.toFile(), manual);

        SpecMerger merger = new SpecMerger();
        merger.merge(skeletonFile, manualFile, outputFile);

        String output = Files.readString(outputFile);
        assertTrue(output.contains("Hand-maintained request schema documentation"),
                "Manual component schema description should be preserved");
        assertTrue(output.contains("model:"), "Skeleton schema properties should be preserved");
        assertFalse(output.contains("Auto-generated from Java"),
                "Skeleton schema description should not override manual");
    }

    @Test
    void nestedPropertyDescriptionPreservedInComponentsSchemas(@TempDir Path tempDir) throws IOException {
        ObjectNode skeleton = mapper.createObjectNode();
        ObjectNode skeletonComponents = mapper.createObjectNode();
        ObjectNode skeletonSchemas = mapper.createObjectNode();
        ObjectNode skeletonSchema = mapper.createObjectNode();
        skeletonSchema.put("type", "object");
        ObjectNode skeletonProperties = mapper.createObjectNode();
        ObjectNode messagesProp = mapper.createObjectNode();
        messagesProp.put("type", "array");
        skeletonProperties.set("messages", messagesProp);
        skeletonSchema.set("properties", skeletonProperties);
        skeletonSchemas.set("ChatRequest", skeletonSchema);
        skeletonComponents.set("schemas", skeletonSchemas);
        skeleton.set("components", skeletonComponents);

        ObjectNode manual = mapper.createObjectNode();
        ObjectNode manualComponents = mapper.createObjectNode();
        ObjectNode manualSchemas = mapper.createObjectNode();
        ObjectNode manualSchema = mapper.createObjectNode();
        ObjectNode manualProperties = mapper.createObjectNode();
        ObjectNode manualMessages = mapper.createObjectNode();
        manualMessages.put("description", "Conversation messages in OpenAI format");
        manualProperties.set("messages", manualMessages);
        manualSchema.set("properties", manualProperties);
        manualSchemas.set("ChatRequest", manualSchema);
        manualComponents.set("schemas", manualSchemas);
        manual.set("components", manualComponents);

        Path skeletonFile = tempDir.resolve("skeleton.yaml");
        Path manualFile = tempDir.resolve("manual.yaml");
        Path outputFile = tempDir.resolve("output.yaml");

        mapper.writeValue(skeletonFile.toFile(), skeleton);
        mapper.writeValue(manualFile.toFile(), manual);

        SpecMerger merger = new SpecMerger();
        merger.merge(skeletonFile, manualFile, outputFile);

        String output = Files.readString(outputFile);
        assertTrue(output.contains("Conversation messages in OpenAI format"),
                "Manual property description should be preserved under components.schemas");
        assertTrue(output.contains("type: array"), "Skeleton property structure should be preserved");
    }

    @Test
    void inlineSchemaDescriptionPreservedWhenSkeletonHasRefOnly(@TempDir Path tempDir) throws IOException {
        ObjectNode skeleton = mapper.createObjectNode();
        ObjectNode skeletonPaths = mapper.createObjectNode();
        ObjectNode skeletonPathItem = mapper.createObjectNode();
        ObjectNode skeletonPost = mapper.createObjectNode();
        skeletonPost.put("operationId", "createChat");
        ObjectNode skeletonReqBody = mapper.createObjectNode();
        ObjectNode skeletonContent = mapper.createObjectNode();
        ObjectNode skeletonJson = mapper.createObjectNode();
        ObjectNode skeletonSchema = mapper.createObjectNode();
        skeletonSchema.put("$ref", "#/components/schemas/ChatRequest");
        skeletonJson.set("schema", skeletonSchema);
        skeletonContent.set("application/json", skeletonJson);
        skeletonReqBody.set("content", skeletonContent);
        skeletonPost.set("requestBody", skeletonReqBody);
        skeletonPathItem.set("post", skeletonPost);
        skeletonPaths.set("/v1/chat", skeletonPathItem);
        skeleton.set("paths", skeletonPaths);

        ObjectNode manual = mapper.createObjectNode();
        ObjectNode manualPaths = mapper.createObjectNode();
        ObjectNode manualPathItem = mapper.createObjectNode();
        ObjectNode manualPost = mapper.createObjectNode();
        manualPost.put("operationId", "createChat");
        ObjectNode manualReqBody = mapper.createObjectNode();
        ObjectNode manualContent = mapper.createObjectNode();
        ObjectNode manualJson = mapper.createObjectNode();
        ObjectNode manualSchema = mapper.createObjectNode();
        manualSchema.put("description", "Inline schema documentation from manual spec");
        manualJson.set("schema", manualSchema);
        manualContent.set("application/json", manualJson);
        manualReqBody.set("content", manualContent);
        manualPost.set("requestBody", manualReqBody);
        manualPathItem.set("post", manualPost);
        manualPaths.set("/v1/chat", manualPathItem);
        manual.set("paths", manualPaths);

        Path skeletonFile = tempDir.resolve("skeleton.yaml");
        Path manualFile = tempDir.resolve("manual.yaml");
        Path outputFile = tempDir.resolve("output.yaml");

        mapper.writeValue(skeletonFile.toFile(), skeleton);
        mapper.writeValue(manualFile.toFile(), manual);

        SpecMerger merger = new SpecMerger();
        merger.merge(skeletonFile, manualFile, outputFile);

        String output = Files.readString(outputFile);
        assertTrue(output.contains("Inline schema documentation from manual spec"),
                "Manual description on inline schema should be preserved when skeleton uses $ref");
        assertTrue(output.contains("ChatRequest"), "Skeleton $ref should be preserved");
    }

    @Test
    void streamingResponseSchemaDoesNotMergeRefWithStructuralSchema(@TempDir Path tempDir) throws IOException {
        ObjectNode skeleton = mapper.createObjectNode();
        ObjectNode skeletonPaths = mapper.createObjectNode();
        ObjectNode skeletonPathItem = mapper.createObjectNode();
        ObjectNode skeletonPost = mapper.createObjectNode();
        skeletonPost.put("operationId", "createChatCompletion");
        ObjectNode skeletonResponses = mapper.createObjectNode();
        ObjectNode skeleton200 = mapper.createObjectNode();
        skeleton200.put("description", "Success");
        ObjectNode skeletonContent = mapper.createObjectNode();
        ObjectNode skeletonEventStream = mapper.createObjectNode();
        ObjectNode skeletonSchema = mapper.createObjectNode();
        skeletonSchema.put("$ref", "#/components/schemas/CreateChatCompletionResponse");
        skeletonEventStream.set("schema", skeletonSchema);
        skeletonContent.set("text/event-stream", skeletonEventStream);
        skeleton200.set("content", skeletonContent);
        skeletonResponses.set("200", skeleton200);
        skeletonPost.set("responses", skeletonResponses);
        skeletonPathItem.set("post", skeletonPost);
        skeletonPaths.set("/openai/deployments/{deployment_name}/chat/completions", skeletonPathItem);
        skeleton.set("paths", skeletonPaths);

        ObjectNode manual = mapper.createObjectNode();
        ObjectNode manualPaths = mapper.createObjectNode();
        ObjectNode manualPathItem = mapper.createObjectNode();
        ObjectNode manualPost = mapper.createObjectNode();
        manualPost.put("operationId", "createChatCompletion");
        ObjectNode manualResponses = mapper.createObjectNode();
        ObjectNode manual200 = mapper.createObjectNode();
        manual200.put("description", "Success");
        ObjectNode manualContent = mapper.createObjectNode();
        ObjectNode manualEventStream = mapper.createObjectNode();
        ObjectNode manualSchema = mapper.createObjectNode();
        manualSchema.put("type", "array");
        ObjectNode manualItems = mapper.createObjectNode();
        manualItems.put("$ref", "#/components/schemas/CreateChatCompletionStreamResponse");
        manualSchema.set("items", manualItems);
        manualEventStream.set("schema", manualSchema);
        manualContent.set("text/event-stream", manualEventStream);
        manual200.set("content", manualContent);
        manualResponses.set("200", manual200);
        manualPost.set("responses", manualResponses);
        manualPathItem.set("post", manualPost);
        manualPaths.set("/openai/deployments/{deployment_name}/chat/completions", manualPathItem);
        manual.set("paths", manualPaths);

        Path skeletonFile = tempDir.resolve("skeleton.yaml");
        Path manualFile = tempDir.resolve("manual.yaml");
        Path outputFile = tempDir.resolve("output.yaml");

        mapper.writeValue(skeletonFile.toFile(), skeleton);
        mapper.writeValue(manualFile.toFile(), manual);

        SpecMerger merger = new SpecMerger();
        merger.merge(skeletonFile, manualFile, outputFile);

        String output = Files.readString(outputFile);
        assertTrue(output.contains("CreateChatCompletionResponse"),
                "Skeleton $ref should be preserved for text/event-stream schema");
        assertFalse(output.contains("CreateChatCompletionStreamResponse"),
                "Manual structural schema should NOT override skeleton $ref");
        assertFalse(output.contains("type: array"),
                "Manual array structure should NOT override skeleton $ref");
    }
}