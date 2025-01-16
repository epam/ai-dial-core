package com.epam.aidial.core.config.validation;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
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
            URI schemaId = application.getApplicationTypeSchemaId();
            if (schemaId == null) {
                continue;
            }

            JsonSchema schema = schemaFactory.getSchema(schemaId);
            if (application.getApplicationProperties() == null) {
                continue;
            }
            JsonNode applicationNode = mapper.valueToTree(application.getApplicationProperties());
            Set<ValidationMessage> validationResults = schema.validate(applicationNode);
            if (!validationResults.isEmpty()) {
                String logMessage = validationResults.stream()
                        .map(ValidationMessage::getMessage)
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("Unknown validation error");
                log.error("Application {} does not conform to schema {}: {}", entry.getKey(), schemaId, logMessage);
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                        .addPropertyNode("applications")
                        .addContainerElementNode(entry.getKey(), Map.class, 0)
                        .addConstraintViolation();
                return false;
            }
        }
        return true;
    }
}