package com.epam.aidial.core.config.validation;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Router;
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
import java.util.Map;
import java.util.Set;

import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.getMetaschemaBuilder;

@Slf4j
public class CustomApplicationsConformToTypeSchemasValidator implements ConstraintValidator<CustomApplicationsConformToTypeSchemas, Config> {


    private static final JsonMetaSchema DIAL_META_SCHEMA = getMetaschemaBuilder()
            .keyword(new NonValidationKeyword("dial:meta"))
            .keyword(new NonValidationKeyword("dial:file"))
            .keyword(new NonValidationKeyword("dial:resource"))
            .build();

    @Override
    public boolean isValid(Config value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7, builder ->
                builder.schemaLoaders(loaders -> loaders.schemas(value.getApplicationTypeSchemas()))
                        .metaSchema(DIAL_META_SCHEMA)
        );

        ObjectMapper mapper = new ObjectMapper();
        for (Map.Entry<String, Application> entry : value.getApplications().entrySet()) {
            Application application = entry.getValue();
            if (!conforms(schemaFactory, mapper, "Application", entry.getKey(), application.getApplicationTypeSchemaId(),
                    application.getApplicationProperties())) {
                return violation(context, "applications", entry.getKey());
            }
        }
        for (Map.Entry<String, Router> entry : value.getRouters().entrySet()) {
            Router router = entry.getValue();
            if (!conforms(schemaFactory, mapper, "Router", entry.getKey(), router.getApplicationTypeSchemaId(),
                    router.getApplicationProperties())) {
                return violation(context, "routers", entry.getKey());
            }
        }
        return true;
    }

    private static boolean conforms(JsonSchemaFactory schemaFactory, ObjectMapper mapper, String kind, String name,
                                    URI schemaId, Map<String, Object> properties) {
        if (schemaId == null) {
            return true;
        }
        JsonSchema schema = schemaFactory.getSchema(schemaId);
        if (properties == null) {
            return true;
        }
        JsonNode node = mapper.valueToTree(properties);
        Set<ValidationMessage> validationResults = schema.validate(node);
        if (validationResults.isEmpty()) {
            return true;
        }
        String logMessage = validationResults.stream()
                .map(ValidationMessage::getMessage)
                .reduce((a, b) -> a + ", " + b)
                .orElse("Unknown validation error");
        log.warn("{} {} does not conform to schema {}: {}", kind, name, schemaId, logMessage);
        return false;
    }

    private static boolean violation(ConstraintValidatorContext context, String map, String name) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode(map)
                .addContainerElementNode(name, Map.class, 0)
                .addConstraintViolation();
        return false;
    }
}