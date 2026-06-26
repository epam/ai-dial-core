package com.epam.aidial.core.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that SpecMerger produces deterministic, ordered output.
 */
class SpecMergerDeterminismTest {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    @TempDir
    Path tempDir;

    @Test
    void mergedPathsAreSortedAlphabetically() throws Exception {
        String skeleton = """
                openapi: 3.0.0
                paths:
                  /z/path:
                    get:
                      summary: Z
                  /a/path:
                    get:
                      summary: A
                  /m/path:
                    get:
                      summary: M
                """;

        String manual = """
                openapi: 3.0.0
                paths:
                  /b/path:
                    get:
                      description: B description
                """;

        String merged = performMerge(skeleton, manual);
        JsonNode root = YAML_MAPPER.readTree(merged);
        JsonNode paths = root.get("paths");

        List<String> pathList = new ArrayList<>();
        paths.fieldNames().forEachRemaining(pathList::add);

        assertEquals(List.of("/a/path", "/b/path", "/m/path", "/z/path"), pathList,
                "Merged paths must be sorted alphabetically");
    }

    @Test
    void mergedComponentSchemasAreSortedAlphabetically() throws Exception {
        String skeleton = """
                openapi: 3.0.0
                components:
                  schemas:
                    ZSchema:
                      type: object
                    ASchema:
                      type: object
                    MSchema:
                      type: object
                """;

        String manual = """
                openapi: 3.0.0
                components:
                  schemas:
                    BSchema:
                      description: B description
                """;

        String merged = performMerge(skeleton, manual);
        JsonNode root = YAML_MAPPER.readTree(merged);
        JsonNode schemas = root.get("components").get("schemas");

        List<String> schemaNames = new ArrayList<>();
        schemas.fieldNames().forEachRemaining(schemaNames::add);

        assertEquals(List.of("ASchema", "BSchema", "MSchema", "ZSchema"), schemaNames,
                "Merged component schemas must be sorted alphabetically");
    }

    @Test
    void mergedResponsesAreSortedByStatusCode() throws Exception {
        String skeleton = """
                openapi: 3.0.0
                paths:
                  /test:
                    get:
                      responses:
                        "500":
                          description: Server error
                        "200":
                          description: Success
                        "404":
                          description: Not found
                        "default":
                          description: Default
                """;

        String manual = """
                openapi: 3.0.0
                paths:
                  /test:
                    get:
                      responses:
                        "401":
                          description: Unauthorized
                """;

        String merged = performMerge(skeleton, manual);
        JsonNode root = YAML_MAPPER.readTree(merged);
        JsonNode responses = root.get("paths").get("/test").get("get").get("responses");

        List<String> responseCodes = new ArrayList<>();
        responses.fieldNames().forEachRemaining(responseCodes::add);

        assertEquals(List.of("200", "401", "404", "500", "default"), responseCodes,
                "Merged responses must be sorted by status code with 'default' last");
    }

    @Test
    void mergedContentTypesAreSortedAlphabetically() throws Exception {
        String skeleton = """
                openapi: 3.0.0
                paths:
                  /test:
                    get:
                      responses:
                        "200":
                          content:
                            text/plain:
                              schema:
                                type: string
                            application/json:
                              schema:
                                type: object
                """;

        String manual = """
                openapi: 3.0.0
                paths:
                  /test:
                    get:
                      responses:
                        "200":
                          content:
                            application/xml:
                              schema:
                                type: object
                """;

        String merged = performMerge(skeleton, manual);
        JsonNode root = YAML_MAPPER.readTree(merged);
        JsonNode content = root.get("paths").get("/test").get("get").get("responses").get("200").get("content");

        List<String> contentTypes = new ArrayList<>();
        content.fieldNames().forEachRemaining(contentTypes::add);

        assertEquals(List.of("application/json", "application/xml", "text/plain"), contentTypes,
                "Merged content types must be sorted alphabetically");
    }

    @Test
    void multipleMergesProduceIdenticalOutput() throws Exception {
        String skeleton = """
                openapi: 3.0.0
                paths:
                  /z:
                    get:
                      responses:
                        "500":
                          description: Error
                        "200":
                          description: OK
                components:
                  schemas:
                    ZModel:
                      type: object
                    AModel:
                      type: object
                """;

        String manual = """
                openapi: 3.0.0
                paths:
                  /a:
                    get:
                      description: A path
                components:
                  schemas:
                    MModel:
                      description: M schema
                """;

        String merged1 = performMerge(skeleton, manual);
        String merged2 = performMerge(skeleton, manual);

        assertEquals(merged1, merged2, "Multiple merges must produce identical output");
    }

    private String performMerge(String skeletonYaml, String manualYaml) throws Exception {
        Path skeletonFile = tempDir.resolve("skeleton.yaml");
        Path manualFile = tempDir.resolve("manual.yaml");
        Path outputFile = tempDir.resolve("output.yaml");

        Files.writeString(skeletonFile, skeletonYaml);
        Files.writeString(manualFile, manualYaml);

        SpecMerger merger = new SpecMerger();
        merger.merge(skeletonFile, manualFile, outputFile);

        return Files.readString(outputFile);
    }
}
