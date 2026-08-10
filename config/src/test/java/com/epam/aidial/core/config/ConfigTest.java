package com.epam.aidial.core.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

public class ConfigTest {

    @Test
    public void testSelectDeployment() {
        Config config = new Config();
        Application app = new Application();
        config.setApplications(Map.of("app", app));
        Model model = new Model();
        config.setModels(Map.of("model", model));
        ToolSet toolSet = new ToolSet();
        config.setToolsets(Map.of("toolset", toolSet));
        Interceptor interceptor = new Interceptor();
        config.setInterceptors(Map.of("interceptor", interceptor));

        assertEquals(app, config.selectDeployment("app"));
        assertEquals(model, config.selectDeployment("model"));
        assertEquals(toolSet, config.selectDeployment("toolset"));
        assertEquals(interceptor, config.selectDeployment("interceptor"));
        assertNull(config.selectDeployment("unknown"));
    }

    @Test
    public void testSelectDeploymentResolvesShortNameAgainstCanonicalId() {
        Config config = new Config();
        Model model = new Model();
        config.setModels(Map.of("models/platform/gpt-4", model));

        assertSame(model, config.selectDeployment("models/platform/gpt-4"), "verbatim (canonical) hit");
        assertSame(model, config.selectDeployment("gpt-4"), "derived (short-name) hit");
        assertNull(config.selectDeployment("unknown"));
    }

    @Test
    public void testGetModelResolvesVerbatimAndDerived() {
        Config config = new Config();
        Model model = new Model();
        config.setModels(Map.of("models/platform/gpt-4", model));

        assertSame(model, config.getModel("models/platform/gpt-4"));
        assertSame(model, config.getModel("gpt-4"));
        assertNull(config.getModel("unknown"));
    }

    @Test
    public void testGetRoleResolvesVerbatimAndDerived() {
        Config config = new Config();
        Role role = new Role();
        config.setRoles(Map.of("roles/platform/admin", role));

        assertSame(role, config.getRole("roles/platform/admin"));
        assertSame(role, config.getRole("admin"));
        assertNull(config.getRole("unknown"));
    }

    @Test
    public void testGetInterceptorResolvesVerbatimAndDerived() {
        Config config = new Config();
        Interceptor interceptor = new Interceptor();
        config.setInterceptors(Map.of("interceptors/platform/my-interceptor", interceptor));

        assertSame(interceptor, config.getInterceptor("interceptors/platform/my-interceptor"));
        assertSame(interceptor, config.getInterceptor("my-interceptor"));
        assertNull(config.getInterceptor("unknown"));
    }

    @Test
    public void testGetCustomApplicationSchemaFallsBackThroughAliasIndex() {
        Config config = new Config();
        String canonicalId = "schemas/platform/my-schema";
        String schemaId = "https://mydial.epam.com/custom_application_schemas/specific_application_type";
        String body = "{\"$id\":\"" + schemaId + "\"}";
        config.setApplicationTypeSchemas(Map.of(canonicalId, body));
        config.setSchemaAliasesById(Map.of(schemaId, canonicalId));

        assertEquals(body, config.getCustomApplicationSchema(URI.create(schemaId)), "$id lookup via alias index");
        assertEquals(body, config.getCustomApplicationSchema(URI.create(canonicalId)), "verbatim canonical-id lookup");
        assertNull(config.getCustomApplicationSchema(URI.create("https://mydial.epam.com/custom_application_schemas/unknown")));
        assertNull(config.getCustomApplicationSchema(null));
    }

    @Test
    public void testGetCatalogSchemaFallsBackThroughAliasIndex() {
        Config config = new Config();
        String canonicalId = "catalog_schemas/platform/my-schema";
        String schemaId = "https://dial.epam.com/catalog-schemas/model";
        String body = "{\"$id\":\"" + schemaId + "\"}";
        config.setCatalogSchemas(Map.of(canonicalId, body));
        config.setCatalogSchemaAliasesById(Map.of(schemaId, canonicalId));

        assertEquals(body, config.getCatalogSchema(URI.create(schemaId)), "$id lookup via alias index");
        assertEquals(body, config.getCatalogSchema(URI.create(canonicalId)), "verbatim canonical-id lookup");
        assertNull(config.getCatalogSchema(URI.create("https://dial.epam.com/catalog-schemas/unknown")));
        assertNull(config.getCatalogSchema(null));
    }
}
