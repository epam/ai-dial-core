package com.epam.aidial.core.metaschemas;

import com.networknt.schema.JsonMetaSchema;
import com.networknt.schema.NonValidationKeyword;
import lombok.experimental.UtilityClass;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@UtilityClass
public class CatalogMetaSchemaHolder {

    public static final String CATALOG_META_SCHEMA_ID = "https://dial.epam.com/catalog_schemas/schema#";
    public static final String CATALOG_ENTITY_TYPE = "dial:catalogEntityType";
    public static final String CATALOG_DISPLAY_NAME = "dial:catalogDisplayName";
    public static final String CATALOG_DEFAULT_LOCALE = "dial:defaultLocale";
    public static final String DIAL_META = "dial:meta";
    public static final String META_TAB = "dial:tab";
    public static final String META_SECTION = "dial:section";
    public static final String META_PROPERTY_ORDER = "dial:propertyOrder";
    public static final String META_WIDGET = "dial:widget";
    public static final String META_LOCALIZED = "dial:localized";
    public static final String CATALOG_SCHEMA_ID_FIELD = "$id";

    public static String getCatalogMetaSchema() {
        try (InputStream inputStream = CatalogMetaSchemaHolder.class.getClassLoader()
                .getResourceAsStream("catalog-schemas/schema.json")) {
            assert inputStream != null;
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load catalog meta schema", e);
        }
    }

    public static JsonMetaSchema.Builder getMetaschemaBuilder() {
        return JsonMetaSchema.builder(CatalogMetaSchemaHolder.CATALOG_META_SCHEMA_ID, JsonMetaSchema.getV7())
                .keyword(new NonValidationKeyword(CATALOG_ENTITY_TYPE))
                .keyword(new NonValidationKeyword(CATALOG_DISPLAY_NAME))
                .keyword(new NonValidationKeyword(CATALOG_DEFAULT_LOCALE))
                .keyword(new NonValidationKeyword(DIAL_META))
                .keyword(new NonValidationKeyword("$defs"))
                .format(new DialFileFormat());
    }
}
