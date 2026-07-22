package com.epam.aidial.core.config.validation;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.LocalizedValue;
import com.epam.aidial.core.config.Model;
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

class CatalogPropertiesConformToSchemasValidatorTest {

    private static final String SCHEMA_ID = "https://dial.epam.com/catalog-schemas/model";

    private static final String VALID_SCHEMA = "{"
            + "\"$schema\": \"https://dial.epam.com/catalog_schemas/schema#\","
            + "\"$id\": \"" + SCHEMA_ID + "\","
            + "\"dial:catalogEntityType\": \"model\","
            + "\"dial:catalogDisplayName\": \"Model\","
            + "\"type\": \"object\","
            + "\"properties\": {"
            + "  \"tag\": { \"type\": \"string\" },"
            + "  \"about\": {"
            + "    \"type\": \"string\","
            + "    \"dial:meta\": { \"dial:localized\": true }"
            + "  }"
            + "}"
            + "}";

    private CatalogPropertiesConformToSchemasValidator validator;

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

    private Config config;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        doNothing().when(context).disableDefaultConstraintViolation();
        when(context.buildConstraintViolationWithTemplate(any())).thenReturn(constraintViolationBuilder);
        when(constraintViolationBuilder.addBeanNode()).thenReturn(leafNodeBuilderCustomizableContext);
        when(leafNodeBuilderCustomizableContext.inIterable()).thenReturn(leafNodeContextBuilder);
        when(leafNodeContextBuilder.atKey(any())).thenReturn(leafNodeBuilderDefinedContext);
        when(leafNodeBuilderDefinedContext.addConstraintViolation()).thenReturn(context);
        validator = new CatalogPropertiesConformToSchemasValidator();
        config = new Config();
    }

    @Test
    void isValidReturnsTrueWhenConfigIsNull() {
        assertTrue(validator.isValid(null, context));
    }

    @Test
    void isValidReturnsTrueWhenNoDeployments() {
        assertTrue(validator.isValid(config, context));
    }

    @Test
    void isValidSkipsModelWithoutCatalogSchemaId() {
        Map<String, Model> models = new HashMap<>();
        models.put("model1", new Model());
        config.setModels(models);
        assertTrue(validator.isValid(config, context));
    }

    @Test
    void isValidReturnsTrueWhenCatalogPropertiesConformToSchema() {
        Map<String, Model> models = new HashMap<>();
        Model model = new Model();
        model.setCatalogSchemaId(URI.create(SCHEMA_ID));
        model.setCatalogProperties(Map.of("tag", "Featured"));
        models.put("model1", model);
        config.setModels(models);
        config.setCatalogSchemas(Map.of(SCHEMA_ID, VALID_SCHEMA));

        assertTrue(validator.isValid(config, context));
    }

    @Test
    void isValidReturnsFalseWhenCatalogPropertiesDoNotConformToSchema() {
        Map<String, Model> models = new HashMap<>();
        Model model = new Model();
        model.setCatalogSchemaId(URI.create(SCHEMA_ID));
        model.setCatalogProperties(Map.of("tag", 123));
        models.put("model1", model);
        config.setModels(models);
        config.setCatalogSchemas(Map.of(SCHEMA_ID, VALID_SCHEMA));

        assertFalse(validator.isValid(config, context));
    }

    @Test
    void isValidReturnsFalseWhenLocalizedCatalogPropertyMissingDefaultLocale() {
        Map<String, Model> models = new HashMap<>();
        Model model = new Model();
        model.setCatalogSchemaId(URI.create(SCHEMA_ID));
        model.setCatalogProperties(Map.of("about", Map.of("de", "Modell")));
        models.put("model1", model);
        config.setModels(models);
        config.setCatalogSchemas(Map.of(SCHEMA_ID, VALID_SCHEMA));

        assertFalse(validator.isValid(config, context));
    }

    @Test
    void isValidReturnsFalseWhenBaseFieldLocaleMapMissingDefaultLocale() {
        Map<String, Model> models = new HashMap<>();
        Model model = new Model();
        model.setDescription(LocalizedValue.of(Map.of("de", "Modell")));
        models.put("model1", model);
        config.setModels(models);

        assertFalse(validator.isValid(config, context));
    }

    @Test
    void isValidReturnsTrueWhenBaseFieldLocaleMapContainsDefaultLocale() {
        Map<String, Model> models = new HashMap<>();
        Model model = new Model();
        model.setDescription(LocalizedValue.of(Map.of("en", "Model", "de", "Modell")));
        models.put("model1", model);
        config.setModels(models);

        assertTrue(validator.isValid(config, context));
    }

    @Test
    void isValidReturnsTrueForEmptyApplicationsMap() {
        config.setApplications(Collections.emptyMap());
        assertTrue(validator.isValid(config, context));
    }
}
