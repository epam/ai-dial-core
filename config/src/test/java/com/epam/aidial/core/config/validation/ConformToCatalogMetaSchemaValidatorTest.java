package com.epam.aidial.core.config.validation;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

class ConformToCatalogMetaSchemaValidatorTest {

    private ConformToCatalogMetaSchemaValidator validator;

    @Mock
    private ConstraintValidatorContext context;
    @Mock
    private ConstraintValidatorContext.ConstraintViolationBuilder constraintViolationBuilder;
    @Mock
    private ConstraintValidatorContext.ConstraintViolationBuilder.LeafNodeBuilderCustomizableContext leafNodeBuilderCustomizableContext;
    @Mock
    private ConstraintValidatorContext.ConstraintViolationBuilder.LeafNodeContextBuilder leafNodeContextBuilder;
    @Mock
    private ConstraintValidatorContext.ConstraintViolationBuilder.LeafNodeBuilderDefinedContext leafNodeBuilderDefinedContext;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        doNothing().when(context).disableDefaultConstraintViolation();
        when(context.buildConstraintViolationWithTemplate(any())).thenReturn(constraintViolationBuilder);
        when(constraintViolationBuilder.addBeanNode()).thenReturn(leafNodeBuilderCustomizableContext);
        when(leafNodeBuilderCustomizableContext.inContainer(Map.class, 1)).thenReturn(leafNodeBuilderCustomizableContext);
        when(leafNodeBuilderCustomizableContext.inIterable()).thenReturn(leafNodeContextBuilder);
        when(leafNodeContextBuilder.atKey(any())).thenReturn(leafNodeBuilderDefinedContext);
        when(leafNodeBuilderDefinedContext.addConstraintViolation()).thenReturn(context);
        validator = new ConformToCatalogMetaSchemaValidator();
    }

    @Test
    void isValidReturnsTrueWhenMapIsNull() {
        assertTrue(validator.isValid(null, context));
    }

    @Test
    void isValidReturnsTrueWhenMapIsEmpty() {
        assertTrue(validator.isValid(Collections.emptyMap(), context));
    }

    @Test
    void isValidReturnsFalseWhenSchemaValidationFails() {
        Map<String, String> invalidMap = new HashMap<>();
        invalidMap.put("invalidKey", "{\"invalid\": \"json\"}");
        assertFalse(validator.isValid(invalidMap, context));
    }

    @Test
    void isValidReturnsTrueWhenSchemaValidationPasses() {
        Map<String, String> validMap = new HashMap<>();
        String validSchema = "{"
                + "\"$schema\": \"https://dial.epam.com/catalog_schemas/schema#\","
                + "\"$id\": \"https://dial.epam.com/catalog-schemas/model\","
                + "\"dial:catalogEntityType\": \"model\","
                + "\"dial:catalogDisplayName\": \"Model\","
                + "\"type\": \"object\","
                + "\"properties\": {"
                + "  \"badge\": {"
                + "    \"type\": \"string\","
                + "    \"format\": \"dial-file-encoded\","
                + "    \"dial:file\": true,"
                + "    \"dial:meta\": {"
                + "      \"dial:tab\": \"Summary\","
                + "      \"dial:widget\": \"image\""
                + "    }"
                + "  }"
                + "}"
                + "}";
        validMap.put("validKey", validSchema);
        assertTrue(validator.isValid(validMap, context));
    }
}
