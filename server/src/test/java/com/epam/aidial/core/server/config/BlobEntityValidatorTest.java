package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Model;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlobEntityValidatorTest {

    @Test
    void validateApplication_returnsNoWarnings_whenAllRefsResolve() {
        Config config = new Config();
        config.setInterceptors(Map.of("guard", new Interceptor()));
        config.setApplicationTypeSchemas(Map.of("https://example.com/schema-v1", "{}"));
        config.setModels(Map.of("gpt-4", new Model()));

        Application app = new Application();
        app.setInterceptors(List.of("guard"));
        app.setApplicationTypeSchemaId(URI.create("https://example.com/schema-v1"));
        app.setDependencies(List.of("gpt-4"));

        List<ValidationWarning> warnings = BlobEntityValidator.validate(app, config);

        assertTrue(warnings.isEmpty());
    }

    @Test
    void validateApplication_warnsOnMissingInterceptor() {
        Config config = new Config();
        config.setInterceptors(Map.of("known", new Interceptor()));

        Application app = new Application();
        app.setInterceptors(List.of("known", "missing"));

        List<ValidationWarning> warnings = BlobEntityValidator.validate(app, config);

        assertEquals(1, warnings.size());
        assertEquals("interceptors[1]", warnings.get(0).getField());
        assertEquals("Interceptor 'missing' not found", warnings.get(0).getMessage());
    }

    @Test
    void validateApplication_warnsOnMissingSchema() {
        Config config = new Config();
        config.setApplicationTypeSchemas(Map.of("https://example.com/known", "{}"));

        Application app = new Application();
        app.setApplicationTypeSchemaId(URI.create("https://example.com/missing"));

        List<ValidationWarning> warnings = BlobEntityValidator.validate(app, config);

        assertEquals(1, warnings.size());
        assertEquals("applicationTypeSchemaId", warnings.get(0).getField());
        assertEquals("Schema 'https://example.com/missing' not found", warnings.get(0).getMessage());
    }

    @Test
    void validateApplication_warnsOnMissingDependency() {
        Config config = new Config();
        config.setModels(Map.of("gpt-4", new Model()));

        Application app = new Application();
        app.setDependencies(List.of("gpt-4", "unknown-model"));

        List<ValidationWarning> warnings = BlobEntityValidator.validate(app, config);

        assertEquals(1, warnings.size());
        assertEquals("dependencies[1]", warnings.get(0).getField());
        assertEquals("Dependency 'unknown-model' not found", warnings.get(0).getMessage());
    }

    @Test
    void validateApplication_collectsAllWarningsInOnePass() {
        Config config = new Config();

        Application app = new Application();
        app.setInterceptors(List.of("missing-interceptor"));
        app.setApplicationTypeSchemaId(URI.create("https://example.com/missing-schema"));
        app.setDependencies(List.of("missing-dep"));

        List<ValidationWarning> warnings = BlobEntityValidator.validate(app, config);

        assertEquals(3, warnings.size());
    }

    @Test
    void validateApplication_returnsNoWarnings_whenFieldsAreNullOrEmpty() {
        Config config = new Config();
        Application app = new Application();

        List<ValidationWarning> warnings = BlobEntityValidator.validate(app, config);

        assertTrue(warnings.isEmpty());
    }

}
