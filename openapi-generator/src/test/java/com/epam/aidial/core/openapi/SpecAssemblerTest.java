package com.epam.aidial.core.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecAssemblerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void versionIsParameterized() {
        String version = "1.2.3-test";
        SpecAssembler assembler = new SpecAssembler(version);
        String yaml = assembler.assemble();

        assertNotNull(yaml);
        assertTrue(yaml.contains(version), "YAML should contain the parameterized version");
        assertFalse(yaml.contains("0.42.0-rc"), "YAML should not contain the old hardcoded version");
    }

    @Test
    void convertToSwaggerSchemaPreservesNullable() throws Exception {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "string");
        schema.put("nullable", true);

        Schema<?> result = invokeConvert(schema);
        assertTrue(result.getNullable(), "Should preserve nullable");
    }

    @Test
    void convertToSwaggerSchemaPreservesDefault() throws Exception {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "integer");
        schema.put("default", 42);

        Schema<?> result = invokeConvert(schema);
        assertNotNull(result.getDefault(), "Should preserve default value");
    }

    @Test
    void convertToSwaggerSchemaPreservesPattern() throws Exception {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "string");
        schema.put("pattern", "^[a-z]+$");

        Schema<?> result = invokeConvert(schema);
        assertEquals("^[a-z]+$", result.getPattern(), "Should preserve pattern");
    }

    @Test
    void convertToSwaggerSchemaPreservesMinMax() throws Exception {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "integer");
        schema.put("minimum", 0);
        schema.put("maximum", 100);

        Schema<?> result = invokeConvert(schema);
        assertNotNull(result.getMinimum(), "Should preserve minimum");
        assertNotNull(result.getMaximum(), "Should preserve maximum");
    }

    @Test
    void convertToSwaggerSchemaPreservesStringLengths() throws Exception {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "string");
        schema.put("minLength", 1);
        schema.put("maxLength", 255);

        Schema<?> result = invokeConvert(schema);
        assertEquals(1, result.getMinLength(), "Should preserve minLength");
        assertEquals(255, result.getMaxLength(), "Should preserve maxLength");
    }

    @Test
    void convertToSwaggerSchemaHandlesTypeArray() throws Exception {
        ObjectNode schema = mapper.createObjectNode();
        var typeArray = mapper.createArrayNode();
        typeArray.add("string");
        typeArray.add("null");
        schema.set("type", typeArray);

        Schema<?> result = invokeConvert(schema);
        assertTrue(result.getNullable(), "Should convert type array with null to nullable");
        assertEquals("string", result.getType(), "Should extract non-null type");
    }

    @Test
    void convertToSwaggerSchemaHandlesConst() throws Exception {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("const", "fixed_value");

        Schema<?> result = invokeConvert(schema);
        assertNotNull(result.getEnum(), "Should convert const to enum");
        assertEquals(1, result.getEnum().size(), "Enum should have single value");
        assertEquals("fixed_value", result.getEnum().get(0), "Enum value should match const");
    }

    @Test
    void convertToSwaggerSchemaHandlesNumericConst() throws Exception {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("const", 42);

        Schema<?> result = invokeConvert(schema);
        assertNotNull(result.getEnum(), "Should convert const to enum");
        assertEquals(1, result.getEnum().size(), "Enum should have single value");
        assertEquals(42, ((Number) result.getEnum().get(0)).intValue(), "Numeric const should preserve type");
    }

    @Test
    void convertToSwaggerSchemaHandlesBooleanConst() throws Exception {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("const", true);

        Schema<?> result = invokeConvert(schema);
        assertNotNull(result.getEnum(), "Should convert const to enum");
        assertEquals(1, result.getEnum().size(), "Enum should have single value");
        assertEquals(true, result.getEnum().get(0), "Boolean const should preserve type");
    }

    @Test
    void convertToSwaggerSchemaPreservesDeprecated() throws Exception {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "string");
        schema.put("deprecated", true);

        Schema<?> result = invokeConvert(schema);
        assertTrue(result.getDeprecated(), "Should preserve deprecated");
    }

    @Test
    void convertToSwaggerSchemaPreservesReadOnly() throws Exception {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "string");
        schema.put("readOnly", true);

        Schema<?> result = invokeConvert(schema);
        assertTrue(result.getReadOnly(), "Should preserve readOnly");
    }

    @Test
    void convertToSwaggerSchemaPreservesTitle() throws Exception {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.put("title", "MySchema");

        Schema<?> result = invokeConvert(schema);
        assertEquals("MySchema", result.getTitle(), "Should preserve title");
    }

    @Test
    void errorResponsesIncluded() {
        SpecAssembler assembler = new SpecAssembler("1.0.0");
        String yaml = assembler.assemble();

        assertTrue(yaml.contains("\"401\"") || yaml.contains("'401'") || yaml.contains("401:"),
                "Should include 401 error response");
        assertTrue(yaml.contains("\"403\"") || yaml.contains("'403'") || yaml.contains("403:"),
                "Should include 403 error response");
        assertTrue(yaml.contains("\"500\"") || yaml.contains("'500'") || yaml.contains("500:"),
                "Should include 500 error response");
    }

    @Test
    void uploadFileRequestBodyIsMultipartBinary() {
        SpecAssembler assembler = new SpecAssembler("1.0.0");
        String yaml = assembler.assemble();

        int uploadStart = yaml.indexOf("operationId: uploadFile");
        assertTrue(uploadStart >= 0, "uploadFile operation should be present");
        int nextOperation = yaml.indexOf("operationId:", uploadStart + 1);
        String uploadSection = yaml.substring(uploadStart, nextOperation);

        assertTrue(uploadSection.contains("multipart/form-data"));
        assertTrue(uploadSection.contains("format: binary"));
        assertTrue(uploadSection.contains("file:"));
        assertTrue(uploadSection.contains("required: true"));
        assertFalse(uploadSection.contains("OpenApiBinary"),
                "uploadFile request body must not reference OpenApiBinary component schema");
    }

    private Schema<?> invokeConvert(ObjectNode schemaNode) throws Exception {
        SpecAssembler assembler = new SpecAssembler("1.0.0");
        var method = SpecAssembler.class.getDeclaredMethod("convertToSwaggerSchema", ObjectNode.class);
        method.setAccessible(true);
        return (Schema<?>) method.invoke(assembler, schemaNode);
    }
}