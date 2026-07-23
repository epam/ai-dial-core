package com.epam.aidial.core.config.validation;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.LocalizedValue;
import com.epam.aidial.core.metaschemas.CatalogMetaSchemaHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonMetaSchema;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.NonValidationKeyword;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class CatalogPropertiesConformToSchemasValidator implements ConstraintValidator<CatalogPropertiesConformToSchemas, Config> {

    private static final JsonMetaSchema CATALOG_DIAL_META_SCHEMA = CatalogMetaSchemaHolder.getMetaschemaBuilder()
            .keyword(new NonValidationKeyword("dial:file"))
            .build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public boolean isValid(Config value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }

        String defaultLocale = value.getDefaultLocale();
        JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7, builder ->
                builder.schemaLoaders(loaders -> loaders.schemas(value.getCatalogSchemas()))
                        .metaSchema(CATALOG_DIAL_META_SCHEMA)
        );

        for (Map.Entry<String, Deployment> entry : allDeployments(value).toList()) {
            Deployment deployment = entry.getValue();

            if (!isBaseFieldLocalizationValid(deployment, defaultLocale)) {
                return reportViolation(context, entry.getKey(),
                        "should contain the default locale \"" + defaultLocale + "\" when displayName/description/intro is a locale map");
            }

            URI schemaId = deployment.getCatalogSchemaId();
            if (schemaId == null || deployment.getCatalogProperties() == null) {
                continue;
            }

            JsonSchema schema = schemaFactory.getSchema(schemaId);
            JsonNode propertiesNode = MAPPER.valueToTree(deployment.getCatalogProperties());
            Set<ValidationMessage> validationResults = schema.validate(propertiesNode);
            if (!validationResults.isEmpty()) {
                String logMessage = validationResults.stream()
                        .map(ValidationMessage::getMessage)
                        .collect(Collectors.joining(", "));
                log.warn("Deployment {} does not conform to catalog schema {}: {}", entry.getKey(), schemaId, logMessage);
                return reportViolation(context, entry.getKey(), "does not conform to catalog schema " + schemaId);
            }

            JsonNode schemaNode = schema.getSchemaNode();
            String schemaDefaultLocale = schemaNode.path(CatalogMetaSchemaHolder.CATALOG_DEFAULT_LOCALE).asText("en");
            for (String localizedField : findLocalizedFieldNames(schemaNode)) {
                JsonNode fieldValue = propertiesNode.get(localizedField);
                if (fieldValue != null && fieldValue.isObject() && !fieldValue.has(schemaDefaultLocale)) {
                    return reportViolation(context, entry.getKey(),
                            "catalog_properties." + localizedField + " should contain the default locale \"" + schemaDefaultLocale + "\"");
                }
            }
        }
        return true;
    }

    private static boolean isBaseFieldLocalizationValid(Deployment deployment, String defaultLocale) {
        return isLocalizedValueValid(deployment.getDisplayName(), defaultLocale)
                && isLocalizedValueValid(deployment.getDescription(), defaultLocale)
                && isLocalizedValueValid(deployment.getIntro(), defaultLocale);
    }

    private static boolean isLocalizedValueValid(LocalizedValue value, String defaultLocale) {
        return value == null || !value.isMap() || value.getLocaleMap().containsKey(defaultLocale);
    }

    private static Set<String> findLocalizedFieldNames(JsonNode schemaNode) {
        Set<String> result = new LinkedHashSet<>();
        JsonNode properties = schemaNode.path("properties");
        Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            boolean localized = field.getValue()
                    .path(CatalogMetaSchemaHolder.DIAL_META)
                    .path(CatalogMetaSchemaHolder.META_LOCALIZED)
                    .asBoolean(false);
            if (localized) {
                result.add(field.getKey());
            }
        }
        return result;
    }

    private static boolean reportViolation(ConstraintValidatorContext context, String deploymentKey, String reason) {
        log.warn("Deployment {} {}", deploymentKey, reason);
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addBeanNode()
                .inIterable().atKey(deploymentKey)
                .addConstraintViolation();
        return false;
    }

    private static Stream<Map.Entry<String, Deployment>> allDeployments(Config config) {
        return Stream.of(
                entries(config.getModels()),
                entries(config.getApplications()),
                entries(config.getToolsets()),
                entries(config.getInterceptors())
        ).flatMap(s -> s);
    }

    private static Stream<Map.Entry<String, Deployment>> entries(Map<String, ? extends Deployment> map) {
        return map.entrySet().stream().map(e -> Map.entry(e.getKey(), (Deployment) e.getValue()));
    }
}
