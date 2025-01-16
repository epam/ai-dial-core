package com.epam.aidial.core.config.validation;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

class CustomApplicationsConformToTypeSchemasValidatorTest {

    private CustomApplicationsConformToTypeSchemasValidator validator;

    @Mock
    private ConstraintValidatorContext context;
    @Mock
    private ConstraintValidatorContext.ConstraintViolationBuilder constraintViolationBuilder;
    @Mock
    private ConstraintValidatorContext.ConstraintViolationBuilder.NodeBuilderCustomizableContext nodeBuilderCustomizableContext;
    @Mock
    private ConstraintValidatorContext.ConstraintViolationBuilder.ContainerElementNodeBuilderCustomizableContext containerElementNodeBuilderCustomizableContext;

    private Config config;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        doNothing().when(context).disableDefaultConstraintViolation();
        when(context.buildConstraintViolationWithTemplate(any())).thenReturn(constraintViolationBuilder);
        when(constraintViolationBuilder.addPropertyNode(any())).thenReturn(nodeBuilderCustomizableContext);
        when(nodeBuilderCustomizableContext.addContainerElementNode(any(), any(), any())).thenReturn(containerElementNodeBuilderCustomizableContext);
        validator = new CustomApplicationsConformToTypeSchemasValidator();
        config = new Config();
    }

    @Test
    void isValidReturnsTrueWhenConfigIsNull() {
        assertTrue(validator.isValid(null, context));
    }

    @Test
    void isValidReturnsTrueWhenApplicationsAreEmpty() {
        config.setApplications(Collections.emptyMap());

        assertTrue(validator.isValid(config, context));
    }

    @Test
    void isValidReturnsTrueWhenApplicationHasNullProps() {
        Map<String, Application> applications = new HashMap<>();
        Application application = new Application();
        application.setApplicationTypeSchemaId(URI.create("https://mydial.epam.com/custom_application_schemas/specific_application_type"));
        applications.put("app1", application);
        config.setApplications(applications);
        Map<String, String> schemas = new HashMap<>();
        String customSchemaStr = "{"
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
        schemas.put("https://mydial.epam.com/custom_application_schemas/specific_application_type", customSchemaStr);
        config.setApplicationTypeSchemas(schemas);

        assertTrue(validator.isValid(config, context));
    }

    @Test
    void isValidReturnsFalseWhenSchemaValidationFails() {
        Map<String, Application> applications = new HashMap<>();
        Application application = new Application();
        application.setApplicationTypeSchemaId(URI.create("https://mydial.epam.com/custom_application_schemas/specific_application_type"));
        application.setApplicationProperties(Map.of());
        applications.put("app1", application);
        config.setApplications(applications);
        Map<String, String> schemas = new HashMap<>();
        String customSchemaStr = "{"
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
        schemas.put("https://mydial.epam.com/custom_application_schemas/specific_application_type", customSchemaStr);
        config.setApplicationTypeSchemas(schemas);

        assertFalse(validator.isValid(config, context));
    }

    @Test
    void isValidReturnsTrueWhenSchemaValidationPasses() {
        Map<String, Application> applications = new HashMap<>();
        Application application = new Application();
        application.setApplicationTypeSchemaId(URI.create("https://mydial.epam.com/custom_application_schemas/specific_application_type"));
        Map<String, Object> props = new HashMap<>();
        props.put("file", "files/bucket/path/name.ext");
        application.setApplicationProperties(props);
        applications.put("app1", application);
        config.setApplications(applications);
        Map<String, String> schemas = new HashMap<>();
        String customSchemaStr = "{"
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
        schemas.put("https://mydial.epam.com/custom_application_schemas/specific_application_type", customSchemaStr);
        config.setApplicationTypeSchemas(schemas);

        assertTrue(validator.isValid(config, context));
    }

    @Test
    void isValidSkipsApplicationWithoutSchemaId() {
        Map<String, Application> applications = new HashMap<>();
        Application application = new Application();
        applications.put("app1", application);
        config.setApplications(applications);

        assertTrue(validator.isValid(config, context));
    }
}