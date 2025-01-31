package com.epam.aidial.core.metaschemas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonMetaSchema;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.NonValidationKeyword;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.CUSTOM_APPLICATION_META_SCHEMA_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DialFileFormatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String customSchemaStr = "{"
            + "\"$schema\": \"https://dial.epam.com/application_type_schemas/schema#\","
            + "\"$id\": \"https://mydial.epam.com/custom_application_schemas/specific_application_type\","
            + "\"dial:applicationTypeEditorUrl\": \"https://mydial.epam.com/specific_application_type_editor\","
            + "\"dial:applicationTypeDisplayName\": \"Specific Application Type\","
            + "\"dial:applicationTypeCompletionEndpoint\": \"http://specific_application_service/opeani/v1/completion\","
            + "\"properties\": {"
            + "  \"file\": {"
            + "    \"type\": \"string\","
            + "    \"format\": \"dial-file-encoded\","
            + "    \"dial:meta\": {"
            + "      \"dial:propertyKind\": \"client\","
            + "      \"dial:propertyOrder\": 1"
            + "    }"
            + "  }"
            + "},"
            + "\"required\": [\"file\"]"
            + "}";
    private JsonSchemaFactory schemaFactory;

    @BeforeEach
    void setUp() {
        JsonMetaSchema metaSchema = MetaSchemaHolder.getMetaschemaBuilder()
                .keyword(new NonValidationKeyword("dial:meta"))
                .build();
        schemaFactory = JsonSchemaFactory.builder()
                .defaultMetaSchemaIri(CUSTOM_APPLICATION_META_SCHEMA_ID)
                .metaSchema(metaSchema)
                .build();
    }

    @Test
    void sampleApplication_validatesAgainstSchema_ok() throws Exception {
        JsonNode customSchemaNode = MAPPER.readTree(customSchemaStr);
        JsonSchema customSchema = schemaFactory.getSchema(customSchemaNode);
        String sampleObjectStr = "{ \"file\": \"files/DpZGXdhaTxtaR67JyAHgDVkSP3Fo4nvV4FYCWNadE2Ln/valid-file-path/valid-sub-path/valid%20file%20name.ext\" }";
        JsonNode sampleObjectNode = MAPPER.readTree(sampleObjectStr);
        Set<ValidationMessage> customSchemaValidationMessages = customSchema.validate(sampleObjectNode);
        assertTrue(customSchemaValidationMessages.isEmpty(), "Sample app should be valid against custom schema");
    }

    @Test
    void sampleApplicationWithRealFilenameWithBraces_validatesAgainstSchema_ok() throws Exception {
        JsonNode customSchemaNode = MAPPER.readTree(customSchemaStr);
        JsonSchema customSchema = schemaFactory.getSchema(customSchemaNode);
        String sampleObjectStr = "{ \"file\": \"files/2pSUd9nfm2gTvgY9ZXj1Z5cSprWyXp8YpDR2EF1pzUxDxNDmKxBx4dK9BRT8xiHgXp/(TechDoc)%20WalletManager%20Overview.svg\" }";
        JsonNode sampleObjectNode = MAPPER.readTree(sampleObjectStr);
        Set<ValidationMessage> customSchemaValidationMessages = customSchema.validate(sampleObjectNode);
        assertTrue(customSchemaValidationMessages.isEmpty(), "Sample app should be valid against custom schema");
    }


    @Test
    void sampleApplication_validatesAgainstSchema_failed_wrongBucket() throws Exception {
        JsonNode customSchemaNode = MAPPER.readTree(customSchemaStr);
        JsonSchema customSchema = schemaFactory.getSchema(customSchemaNode);
        String sampleObjectStr = "{ \"file\": \"files/wrong bucket/valid-file-path/valid%20file%20name.ext\" }";
        JsonNode sampleObjectNode = MAPPER.readTree(sampleObjectStr);
        Set<ValidationMessage> customSchemaValidationMessages = customSchema.validate(sampleObjectNode);
        assertEquals(1, customSchemaValidationMessages.size(), "Sample app should be invalid against custom schema");
    }

    @Test
    void sampleApplication_validatesAgainstSchema_failed_wrongPath() throws Exception {
        JsonNode customSchemaNode = MAPPER.readTree(customSchemaStr);
        JsonSchema customSchema = schemaFactory.getSchema(customSchemaNode);
        String sampleObjectStr = "{ \"file\": \"files/DpZGXdhaTxtaR67JyAHgDVkSP3Fo4nvV4FYCWNadE2Ln/invalid file path/valid%20file%20name.ext\" }";
        JsonNode sampleObjectNode = MAPPER.readTree(sampleObjectStr);
        Set<ValidationMessage> customSchemaValidationMessages = customSchema.validate(sampleObjectNode);
        assertEquals(1, customSchemaValidationMessages.size(), "Sample app should be invalid against custom schema");
    }

    @Test
    void sampleApplication_validatesAgainstSchema_failed_wrongType() throws Exception {
        JsonNode customSchemaNode = MAPPER.readTree(customSchemaStr);
        JsonSchema customSchema = schemaFactory.getSchema(customSchemaNode);
        String sampleObjectStr = "{ \"file\": \"applications/DpZGXdhaTxtaR67JyAHgDVkSP3Fo4nvV4FYCWNadE2Ln/valid-file-path/valid%20file%20name.ext\" }";
        JsonNode sampleObjectNode = MAPPER.readTree(sampleObjectStr);
        Set<ValidationMessage> customSchemaValidationMessages = customSchema.validate(sampleObjectNode);
        assertEquals(1, customSchemaValidationMessages.size(), "Sample app should be invalid against custom schema");
    }

    @Test
    void sampleApplication_validatesAgainstSchema_failed_empty() throws Exception {
        JsonNode customSchemaNode = MAPPER.readTree(customSchemaStr);
        JsonSchema customSchema = schemaFactory.getSchema(customSchemaNode);
        String sampleObjectStr = "{ \"file\": \"\" }";
        JsonNode sampleObjectNode = MAPPER.readTree(sampleObjectStr);
        Set<ValidationMessage> customSchemaValidationMessages = customSchema.validate(sampleObjectNode);
        assertEquals(1, customSchemaValidationMessages.size(), "Sample app should be invalid against custom schema");
    }
}