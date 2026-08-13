package com.epam.aidial.core.config;

import com.epam.aidial.core.config.databind.JsonArrayToSchemaMapDeserializer;
import com.epam.aidial.core.config.databind.MapToJsonArraySerializer;
import com.epam.aidial.core.config.validation.CatalogPropertiesConformToSchemas;
import com.epam.aidial.core.config.validation.ConformToCatalogMetaSchema;
import com.epam.aidial.core.config.validation.ConformToMetaSchema;
import com.epam.aidial.core.config.validation.CustomApplicationsConformToTypeSchemas;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Data;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@CustomApplicationsConformToTypeSchemas(message = "All custom schema-rich applications should conform to their schemas")
@CatalogPropertiesConformToSchemas(message = "All deployments with catalog_schema_id should conform to their catalog schema")
public class Config {
    // maintain the order of routes defined in the config
    private LinkedHashMap<String, Route> routes = new LinkedHashMap<>();
    private Map<String, Model> models = Map.of();
    private Map<String, Application> applications = Map.of();
    private Map<String, ToolSet> toolsets = Map.of();
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private Map<String, Key> keys = new HashMap<>();
    private Map<String, Role> roles = new HashMap<>();
    private Set<Integer> retriableErrorCodes = Set.of();
    private Map<String, Interceptor> interceptors = Map.of();

    @JsonDeserialize(using = JsonArrayToSchemaMapDeserializer.class)
    @JsonSerialize(using = MapToJsonArraySerializer.class)
    @ConformToMetaSchema(message = "All custom application type schemas should conform to meta schema")
    private Map<String, String> applicationTypeSchemas = Map.of();

    @JsonDeserialize(using = JsonArrayToSchemaMapDeserializer.class)
    @JsonSerialize(using = MapToJsonArraySerializer.class)
    @ConformToCatalogMetaSchema(message = "All catalog schemas should conform to the catalog meta schema")
    private Map<String, String> catalogSchemas = Map.of();

    /**
     * Instance-wide BCP-47 default locale: the locale assigned to legacy plain-string
     * displayName/description/intro values, and the fallback locale used across localized fields.
     */
    @JsonAlias({"defaultLocale", "default_locale"})
    private String defaultLocale = "en";

    private List<String> globalInterceptors = List.of();

    /**
     * $id → canonical-id index for {@link #applicationTypeSchemas}, built at rebuild time from
     * blob bodies: each key is a schema's own {@code $id} (as declared in its body), each value
     * is the canonical id of the blob entry storing that schema. Bridges $id-keyed file entries
     * and canonical-id-keyed blob entries, since a schema's $id is not derivable from its path.
     *
     * <p>For example, given a blob entry stored under canonical id
     * {@code schemas/platform/my-schema} whose body declares
     * {@code "$id": "https://example.com/schemas/my-schema.json"}, this map holds
     * {@code "https://example.com/schemas/my-schema.json" → "schemas/platform/my-schema"}.
     */
    @JsonIgnore
    private Map<String, String> applicationSchemaAliasesById = Map.of();

    @JsonIgnore
    private Map<String, String> catalogSchemaAliasesById = Map.of();

    @JsonIgnore
    public Deployment selectDeployment(String deploymentId) {
        Application application = applications.get(deploymentId);
        if (application != null) {
            return application;
        }

        Model model = models.get(deploymentId);
        if (model != null) {
            return model;
        }

        ToolSet toolSet = toolsets.get(deploymentId);
        if (toolSet != null) {
            return toolSet;
        }

        return interceptors.get(deploymentId);
    }

    public boolean isDeploymentExists(String deploymentId) {
        return selectDeployment(deploymentId) != null;
    }

    /**
     * @return the schema body, or {@code null} if {@code schemaId} is null or unresolved
     */
    @JsonIgnore
    public String getCustomApplicationSchema(URI schemaId) {
        return resolveSchema(applicationTypeSchemas, applicationSchemaAliasesById, schemaId);
    }

    /**
     * @return the schema body, or {@code null} if {@code schemaId} is null or unresolved
     */
    @JsonIgnore
    public String getCatalogSchema(URI schemaId) {
        return resolveSchema(catalogSchemas, catalogSchemaAliasesById, schemaId);
    }

    /**
     * Resolves a schema by its $id: verbatim lookup first (canonical-id callers, and file entries
     * already keyed by $id), then falls back through the $id → canonical-id alias index for a
     * migrated blob entry. A schema's $id is not derivable from its path, so the alias index must
     * be maintained explicitly (see {@code MergedConfigStore}).
     *
     * @return the schema body, or {@code null} if {@code schemaId} is null or unresolved
     */
    private static String resolveSchema(Map<String, String> schemas, Map<String, String> aliasesById, URI schemaId) {
        if (schemaId == null) {
            return null;
        }
        String id = schemaId.toString();
        String body = schemas.get(id);
        if (body != null) {
            return body;
        }
        String canonicalId = aliasesById.get(id);
        return canonicalId == null ? null : schemas.get(canonicalId);
    }
}
