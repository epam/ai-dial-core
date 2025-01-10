package com.epam.aidial.core.metaschemas;

import com.networknt.schema.JsonMetaSchema;
import com.networknt.schema.NonValidationKeyword;
import lombok.experimental.UtilityClass;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@UtilityClass
public class MetaSchemaHolder {

    public static final String CUSTOM_APPLICATION_META_SCHEMA_ID = "https://dial.epam.com/application_type_schemas/schema#";

    public static String getCustomApplicationMetaSchema() {
        try (InputStream inputStream = MetaSchemaHolder.class.getClassLoader()
                .getResourceAsStream("custom-application-schemas/schema")) {
            assert inputStream != null;
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load custom application meta schema", e);
        }
    }

    public static JsonMetaSchema.Builder getMetaschemaBuilder() {
        return JsonMetaSchema.builder(MetaSchemaHolder.CUSTOM_APPLICATION_META_SCHEMA_ID,  JsonMetaSchema.getV7())
                .keyword(new NonValidationKeyword("dial:applicationTypeEditorUrl"))
                .keyword(new NonValidationKeyword("dial:applicationTypeViewerUrl"))
                .keyword(new NonValidationKeyword("dial:applicationTypeDisplayName"))
                .keyword(new NonValidationKeyword("dial:applicationTypeCompletionEndpoint"))
                .keyword(new NonValidationKeyword("dial:propertyKind"))
                .keyword(new NonValidationKeyword("dial:propertyOrder"))
                .keyword(new NonValidationKeyword("$defs"))
                .format(new DialFileFormat());
    }
}
