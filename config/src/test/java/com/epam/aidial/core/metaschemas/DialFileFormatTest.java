package com.epam.aidial.core.metaschemas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonMetaSchema;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.NonValidationKeyword;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.CUSTOM_APPLICATION_META_SCHEMA_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialFileFormatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CUSTOM_SCHEMA = """
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

    static Stream<TestCase> fileValidationCases() {
        return Stream.of(
            new TestCase(
                "{ \"file\": \"files/DpZGXdhaTxtaR67JyAHgDVkSP3Fo4nvV4FYCWNadE2Ln/valid-file-path/valid-sub-path/valid%20file%20name.ext\" }",
                true,
                "Sample app should be valid against custom schema"
            ),
            new TestCase(
                "{ \"file\": \"files/2pSUd9nfm2gTvgY9ZXj1Z5cSprWyXp8YpDR2EF1pzUxDxNDmKxBx4dK9BRT8xiHgXp/(TechDoc)%20WalletManager%20Overview.svg\" }",
                true,
                "Sample app should be valid against custom schema"
            ),
            new TestCase(
                "{ \"file\": \"files/2pSUd9nfm2gTvgY9ZXj1Z5cSprWyXp8YpDR2EF1pzUxDxNDmKxBx4dK9BRT8xiHgXp/image059305%60'12.png\" }",
                true,
                "Sample app should be valid against custom schema"
            ),
            new TestCase(
                "{ \"file\": \"\" }",
                false,
                "Sample app should be invalid against custom schema"
            )
        );
    }

    @ParameterizedTest
    @MethodSource("fileValidationCases")
    void fileField_validatesAgainstSchema_parametrized(TestCase testCase) throws Exception {
        JsonNode customSchemaNode = MAPPER.readTree(CUSTOM_SCHEMA);
        JsonSchema customSchema = schemaFactory.getSchema(customSchemaNode);
        JsonNode sampleObjectNode = MAPPER.readTree(testCase.sampleObjectStr());
        Set<ValidationMessage> validationMessages = customSchema.validate(sampleObjectNode);
        if (testCase.shouldBeValid()) {
            assertTrue(validationMessages.isEmpty(), testCase.message());
        } else {
            assertEquals(1, validationMessages.size(), testCase.message());
        }
    }

    private record TestCase(String sampleObjectStr, boolean shouldBeValid, String message) {}
}