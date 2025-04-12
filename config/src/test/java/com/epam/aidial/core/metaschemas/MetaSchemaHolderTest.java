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

public class MetaSchemaHolderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private JsonSchema jsonMetaSchema;

    @BeforeEach
    void setUp() {
        JsonMetaSchema metaSchema = MetaSchemaHolder.getMetaschemaBuilder()
                .keyword(new NonValidationKeyword("dial:meta"))
                .build();
        JsonSchemaFactory schemaFactory = JsonSchemaFactory.builder()
                .defaultMetaSchemaIri(CUSTOM_APPLICATION_META_SCHEMA_ID)
                .metaSchema(metaSchema)
                .build();
        jsonMetaSchema = schemaFactory
                .getSchema(MetaSchemaHolder.getCustomApplicationMetaSchema());
    }

    @Test
    void customSchema_validatesAgainstMetaSchema_ok_schemaConforms() throws Exception {
        String customSchemaStr = """
                {\
                "$schema": "https://dial.epam.com/application_type_schemas/schema#",\
                "$id": "https://mydial.epam.com/custom_application_schemas/specific_application_type",\
                "dial:applicationTypeEditorUrl": "https://mydial.epam.com/specific_application_type_editor",\
                "dial:applicationTypeDisplayName": "Specific Application Type",\
                "dial:applicationTypeCompletionEndpoint": "http://specific_application_service/openai/v1/completion",\
                "properties": {\
                  "file": {\
                    "type": "string",\
                    "format": "dial-file-encoded",\
                    "dial:meta": {\
                      "dial:propertyKind": "client",\
                      "dial:propertyOrder": 1\
                    }\
                  }\
                },\
                "required": ["file"]\
                }""";
        JsonNode customSchemaNode = MAPPER.readTree(customSchemaStr);
        Set<ValidationMessage> metaSchemaValidationMessages = jsonMetaSchema.validate(customSchemaNode);
        assertTrue(metaSchemaValidationMessages.isEmpty(), "Custom schema should be valid against meta schema");
    }

    @Test
    void customSchema_validatesAgainstMetaSchema_failed_noMeta() throws Exception {
        String invalidCustomSchemaStr = """
                {\
                "$schema": "https://dial.epam.com/application_type_schemas/schema#",\
                "$id": "https://mydial.epam.com/custom_application_schemas/specific_application_type",\
                "dial:applicationTypeEditorUrl": "https://mydial.epam.com/specific_application_type_editor",\
                "dial:applicationTypeDisplayName": "Specific Application Type",\
                "dial:applicationTypeCompletionEndpoint": "http://specific_application_service/openai/v1/completion",\
                "properties": {\
                  "file": {\
                    "type": "string",\
                    "format": "dial-file-encoded"\
                  }\
                },\
                "required": ["file"]\
                }""";
        JsonNode customSchemaNode = MAPPER.readTree(invalidCustomSchemaStr);
        Set<ValidationMessage> metaSchemaValidationMessages = jsonMetaSchema.validate(customSchemaNode);
        assertEquals(1, metaSchemaValidationMessages.size(), "Custom schema should be invalid against"
                + " meta schema because of a single reason");
    }

    @Test
    void customSchema_validatesAgainstMetaSchema_failed_wrongDialFileType() throws Exception {
        String invalidCustomSchemaStr = """
                {\
                "$schema": "https://dial.epam.com/application_type_schemas/schema#",\
                "$id": "https://mydial.epam.com/custom_application_schemas/specific_application_type",\
                "dial:applicationTypeEditorUrl": "https://mydial.epam.com/specific_application_type_editor",\
                "dial:applicationTypeDisplayName": "Specific Application Type",\
                "dial:applicationTypeCompletionEndpoint": "http://specific_application_service/openai/v1/completion",\
                "properties": {\
                  "file": {\
                    "type": "object",\
                    "format": "dial-file-encoded",\
                    "dial:meta": {\
                      "dial:propertyKind": "client",\
                      "dial:propertyOrder": 1\
                    },\
                    "dial:file": true\
                  }\
                },\
                "required": ["file"]\
                }""";
        JsonNode customSchemaNode = MAPPER.readTree(invalidCustomSchemaStr);
        Set<ValidationMessage> metaSchemaValidationMessages = jsonMetaSchema.validate(customSchemaNode);
        assertEquals(1, metaSchemaValidationMessages.size(), "Custom schema should be invalid against"
                + " meta schema because of a single reason");
    }

    @Test
    void customSchema_validatesAgainstMetaSchema_failed_wrongKind() throws Exception {
        String invalidCustomSchemaStr = """
                {\
                "$schema": "https://dial.epam.com/application_type_schemas/schema#",\
                "$id": "https://mydial.epam.com/custom_application_schemas/specific_application_type",\
                "dial:applicationTypeEditorUrl": "https://mydial.epam.com/specific_application_type_editor",\
                "dial:applicationTypeDisplayName": "Specific Application Type",\
                "dial:applicationTypeCompletionEndpoint": "http://specific_application_service/openai/v1/completion",\
                "properties": {\
                  "file": {\
                    "type": "string",\
                    "format": "dial-file-encoded",\
                    "dial:meta": {\
                      "dial:propertyKind": "client-server",\
                      "dial:propertyOrder": 1\
                    }\
                  }\
                },\
                "required": ["file"]\
                }""";
        JsonNode customSchemaNode = MAPPER.readTree(invalidCustomSchemaStr);
        Set<ValidationMessage> metaSchemaValidationMessages = jsonMetaSchema.validate(customSchemaNode);
        assertEquals(1, metaSchemaValidationMessages.size(), "Custom schema should be invalid against"
                + " meta schema because of a single reason");
    }

    @Test
    void customSchema_validatesAgainstMetaSchema_failed_wrongCount() throws Exception {
        String invalidCustomSchemaStr = """
                {\
                "$schema": "https://dial.epam.com/application_type_schemas/schema#",\
                "$id": "https://mydial.epam.com/custom_application_schemas/specific_application_type",\
                "dial:applicationTypeEditorUrl": "https://mydial.epam.com/specific_application_type_editor",\
                "dial:applicationTypeDisplayName": "Specific Application Type",\
                "dial:applicationTypeCompletionEndpoint": "http://specific_application_service/openai/v1/completion",\
                "properties": {\
                  "file": {\
                    "type": "string",\
                    "format": "dial-file-encoded",\
                    "dial:meta": {\
                      "dial:propertyKind": "client",\
                      "dial:propertyOrder": "1"\
                    }\
                  }\
                },\
                "required": ["file"]\
                }""";
        JsonNode customSchemaNode = MAPPER.readTree(invalidCustomSchemaStr);
        Set<ValidationMessage> metaSchemaValidationMessages = jsonMetaSchema.validate(customSchemaNode);
        assertEquals(1, metaSchemaValidationMessages.size(), "Custom schema should be invalid against"
                + " meta schema because of a single reason");
    }

    @Test
    void customSchema_validatesAgainstMetaSchema_failed_notTopLayerMeta() throws Exception {
        String invalidCustomSchemaStr = """
                {\
                "$schema": "https://dial.epam.com/application_type_schemas/schema#",\
                "$id": "https://mydial.epam.com/custom_application_schemas/specific_application_type",\
                "dial:applicationTypeEditorUrl": "https://mydial.epam.com/specific_application_type_editor",\
                "dial:applicationTypeDisplayName": "Specific Application Type",\
                "dial:applicationTypeCompletionEndpoint": "http://specific_application_service/openai/v1/completion",\
                "properties": {\
                  "foo": {\
                    "type": "object",\
                    "dial:meta": {\
                        "dial:propertyKind": "client",\
                        "dial:propertyOrder": 1\
                      },\
                    "properties": {\
                      "file": {\
                        "type": "string",\
                        "format": "dial-file-encoded",\
                        "dial:meta": {\
                             "dial:propertyKind": "client",\
                             "dial:propertyOrder": 1\
                         }\
                      }\
                    },\
                    "required": ["file"]\
                  }\
                },\
                "required": ["foo"]\
                }""";
        JsonNode customSchemaNode = MAPPER.readTree(invalidCustomSchemaStr);
        Set<ValidationMessage> metaSchemaValidationMessages = jsonMetaSchema.validate(customSchemaNode);
        assertEquals(1, metaSchemaValidationMessages.size(), "Custom schema should be invalid against"
                + " meta schema because of a single reason");
    }

    @Test
    void customSchema_validatesAgainstMetaSchema_failed_InvalidFormatOfDialFile() throws Exception {
        String invalidCustomSchemaStr = """
                {\
                "$schema": "https://dial.epam.com/application_type_schemas/schema#",\
                "$id": "https://mydial.epam.com/custom_application_schemas/specific_application_type",\
                "dial:applicationTypeEditorUrl": "https://mydial.epam.com/specific_application_type_editor",\
                "dial:applicationTypeDisplayName": "Specific Application Type",\
                "dial:applicationTypeCompletionEndpoint": "http://specific_application_service/openai/v1/completion",\
                "properties": {\
                  "file": {\
                    "type": "string",\
                    "format": "uri",\
                    "dial:meta": {\
                      "dial:propertyKind": "client",\
                      "dial:propertyOrder": 1\
                    },\
                    "dial:file": true\
                  }\
                },\
                "required": ["file"]\
                }""";
        JsonNode customSchemaNode = MAPPER.readTree(invalidCustomSchemaStr);
        Set<ValidationMessage> metaSchemaValidationMessages = jsonMetaSchema.validate(customSchemaNode);
        assertEquals(1, metaSchemaValidationMessages.size(), "Custom schema should be invalid against"
                + " meta schema because of a single reason");
    }

    @Test
    void customSchema_validatesAgainstMetaSchema_success_EditorUrlAbsent() throws Exception {
        String invalidCustomSchemaStr = """
                {\
                "$schema": "https://dial.epam.com/application_type_schemas/schema#",\
                "$id": "https://mydial.epam.com/custom_application_schemas/specific_application_type",\
                "dial:applicationTypeDisplayName": "Specific Application Type",\
                "dial:applicationTypeCompletionEndpoint": "http://specific_application_service/openai/v1/completion",\
                "properties": {\
                  "file": {\
                    "type": "string",\
                    "format": "dial-file-encoded",\
                    "dial:meta": {\
                      "dial:propertyKind": "client",\
                      "dial:propertyOrder": 1\
                    },\
                    "dial:file": true\
                  }\
                },\
                "required": ["file"]\
                }""";
        JsonNode customSchemaNode = MAPPER.readTree(invalidCustomSchemaStr);
        Set<ValidationMessage> metaSchemaValidationMessages = jsonMetaSchema.validate(customSchemaNode);
        assertEquals(0, metaSchemaValidationMessages.size(), "Custom schema should be ok if editor url absent");
    }

    @Test
    void customSchema_validatesAgainstMetaSchema_failed_displayNameAbsent() throws Exception {
        String invalidCustomSchemaStr = """
                {\
                "$schema": "https://dial.epam.com/application_type_schemas/schema#",\
                "$id": "https://mydial.epam.com/custom_application_schemas/specific_application_type",\
                "dial:applicationTypeEditorUrl": "https://mydial.epam.com/specific_application_type_editor",\
                "dial:applicationTypeCompletionEndpoint": "http://specific_application_service/openai/v1/completion",\
                "properties": {\
                  "file": {\
                    "type": "string",\
                    "format": "dial-file-encoded",\
                    "dial:meta": {\
                      "dial:propertyKind": "client",\
                      "dial:propertyOrder": 1\
                    },\
                    "dial:file": true\
                  }\
                },\
                "required": ["file"]\
                }""";
        JsonNode customSchemaNode = MAPPER.readTree(invalidCustomSchemaStr);
        Set<ValidationMessage> metaSchemaValidationMessages = jsonMetaSchema.validate(customSchemaNode);
        assertEquals(1, metaSchemaValidationMessages.size(), "Custom schema should be invalid against"
                + " meta schema because of a single reason");
    }

    @Test
    void customSchema_validatesAgainstMetaSchema_failed_completionEndpointAbsent() throws Exception {
        String invalidCustomSchemaStr = """
                {\
                "$schema": "https://dial.epam.com/application_type_schemas/schema#",\
                "$id": "https://mydial.epam.com/custom_application_schemas/specific_application_type",\
                "dial:applicationTypeEditorUrl": "https://mydial.epam.com/specific_application_type_editor",\
                "dial:applicationTypeDisplayName": "Specific Application Type",\
                "properties": {\
                  "file": {\
                    "type": "string",\
                    "format": "dial-file-encoded",\
                    "dial:meta": {\
                      "dial:propertyKind": "client",\
                      "dial:propertyOrder": 1\
                    },\
                    "dial:file": true\
                  }\
                },\
                "required": ["file"]\
                }""";
        JsonNode customSchemaNode = MAPPER.readTree(invalidCustomSchemaStr);
        Set<ValidationMessage> metaSchemaValidationMessages = jsonMetaSchema.validate(customSchemaNode);
        assertEquals(1, metaSchemaValidationMessages.size(), "Custom schema should be invalid against"
                + " meta schema because of a single reason");
    }

    @Test
    void customSchema_validatesAgainstMetaSchema_fails_wrong_feature_endpoints() throws Exception {
        String customSchemaStr = """
                {\
                "$schema": "https://dial.epam.com/application_type_schemas/schema#",\
                "$id": "https://mydial.epam.com/custom_application_schemas/specific_application_type",\
                "dial:applicationTypeEditorUrl": "https://mydial.epam.com/specific_application_type_editor",\
                "dial:applicationTypeDisplayName": "Specific Application Type",\
                "dial:applicationTypeCompletionEndpoint": "http://specific_application_service/openai/v1/completion",\
                "dial:applicationTypeConfigurationEndpoint": "!wrong",\
                "dial:applicationTypeRateEndpoint": "!wrong",\
                "dial:applicationTypeTokenizeEndpoint": "!wrong",\
                "dial:applicationTypeTruncatePromptEndpoint": "!wrong",\
                "properties": {\
                  "file": {\
                    "type": "string",\
                    "format": "dial-file-encoded",\
                    "dial:meta": {\
                      "dial:propertyKind": "client",\
                      "dial:propertyOrder": 1\
                    }\
                  }\
                },\
                "required": ["file"]\
                }""";
        JsonNode customSchemaNode = MAPPER.readTree(customSchemaStr);
        Set<ValidationMessage> metaSchemaValidationMessages = jsonMetaSchema.validate(customSchemaNode);
        assertEquals(4, metaSchemaValidationMessages.size(), "Custom schema should be invalid against meta schema with bad features");
    }

    @Test
    void customSchema_validatesAgainstMetaSchema_fails_ok_when_all_features() throws Exception {
        String customSchemaStr = """
                {\
                "$schema": "https://dial.epam.com/application_type_schemas/schema#",\
                "$id": "https://mydial.epam.com/custom_application_schemas/specific_application_type",\
                "dial:applicationTypeEditorUrl": "https://mydial.epam.com/specific_application_type_editor",\
                "dial:applicationTypeDisplayName": "Specific Application Type",\
                "dial:applicationTypeCompletionEndpoint": "http://specific_application_service/completion",\
                "dial:applicationTypeConfigurationEndpoint": "http://specific_application_service/configuration",\
                "dial:applicationTypeRateEndpoint": "http://specific_application_service/rate",\
                "dial:applicationTypeTokenizeEndpoint": "http://specific_application_service/tokenize",\
                "dial:applicationTypeTruncatePromptEndpoint": "http://specific_application_service/truncate",\
                "properties": {\
                  "file": {\
                    "type": "string",\
                    "format": "dial-file-encoded",\
                    "dial:meta": {\
                      "dial:propertyKind": "client",\
                      "dial:propertyOrder": 1\
                    }\
                  }\
                },\
                "required": ["file"]\
                }""";
        JsonNode customSchemaNode = MAPPER.readTree(customSchemaStr);
        Set<ValidationMessage> metaSchemaValidationMessages = jsonMetaSchema.validate(customSchemaNode);
        assertTrue(metaSchemaValidationMessages.isEmpty(), "Custom schema should be valid against meta schema");
    }
}