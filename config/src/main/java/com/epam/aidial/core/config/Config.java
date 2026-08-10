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
     * $id → canonical-id index for {@code platform}-bucket schema entities, built at rebuild
     * time from blob bodies. Bridges $id-keyed file entries and canonical-id-keyed blob entries
     * in {@link #applicationTypeSchemas}, since a schema's $id is not derivable from its path.
     */
    @JsonIgnore
    private Map<String, String> schemaAliasesById = Map.of();

    @JsonIgnore
    private Map<String, String> catalogSchemaAliasesById = Map.of();

    @JsonIgnore
    public Deployment selectDeployment(String deploymentId) {
        Application application = resolve(applications, "applications", deploymentId);
        if (application != null) {
            return application;
        }

        Model model = resolve(models, "models", deploymentId);
        if (model != null) {
            return model;
        }

        ToolSet toolSet = resolve(toolsets, "toolsets", deploymentId);
        if (toolSet != null) {
            return toolSet;
        }

        return resolve(interceptors, "interceptors", deploymentId);
    }

    public boolean isDeploymentExists(String deploymentId) {
        return selectDeployment(deploymentId) != null;
    }

    @JsonIgnore
    public Model getModel(String id) {
        return resolve(models, "models", id);
    }

    @JsonIgnore
    public Role getRole(String id) {
        return resolve(roles, "roles", id);
    }

    @JsonIgnore
    public Interceptor getInterceptor(String id) {
        return resolve(interceptors, "interceptors", id);
    }

    @JsonIgnore
    public String getCustomApplicationSchema(URI schemaId) {
        return resolveSchema(applicationTypeSchemas, schemaAliasesById, schemaId);
    }

    @JsonIgnore
    public String getCatalogSchema(URI schemaId) {
        return resolveSchema(catalogSchemas, catalogSchemaAliasesById, schemaId);
    }

    /**
     * Resolves a schema by its $id: verbatim lookup first (canonical-id callers, and file entries
     * already keyed by $id), then falls back through the $id → canonical-id alias index for a
     * migrated blob entry. A schema's $id is not derivable from its path, so unlike {@link
     * #resolve}, the alias index must be maintained explicitly (see {@code MergedConfigStore}).
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

    /**
     * Resolves a deployment-map lookup by id. Tries {@code id} verbatim first (canonical-id
     * callers, and not-yet-migrated file entries keyed by short name), then falls back to the
     * derived canonical id {@code typeSegment/platform/id} for a short-name lookup against a
     * migrated blob entry. {@code typeSegment} is a string literal because this module has no
     * dependency on storage/ResourceTypes.
     */
    private static <V> V resolve(Map<String, V> entities, String typeSegment, String id) {
        V direct = entities.get(id);
        if (direct != null) {
            return direct;
        }
        return entities.get(typeSegment + "/platform/" + id);
    }
}
