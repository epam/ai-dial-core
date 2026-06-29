package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ConfigPostProcessor}'s slice 2S.9 split:
 * structural slash-keyed-name rejection and semantic skip|abort routing.
 */
public class ConfigPostProcessorTest {

    @Test
    void testStructuralDropsSlashKeyedNamesAcrossAllTypes() {
        Config config = newMutableConfig();
        config.getModels().put("good-model", new Model());
        config.getModels().put("bad/model", new Model());
        config.getApplications().put("good-app", new Application());
        config.getApplications().put("bad/app", new Application());
        config.getInterceptors().put("good-interceptor", new Interceptor());
        config.getInterceptors().put("bad/interceptor", new Interceptor());
        config.getRoles().put("good-role", new Role());
        config.getRoles().put("bad/role", new Role());
        Route route = new Route();
        route.setOrder(1);
        config.getRoutes().put("good-route", route);
        Route badRoute = new Route();
        badRoute.setOrder(2);
        config.getRoutes().put("bad/route", badRoute);
        config.getToolsets().put("good-toolset", new ToolSet());
        config.getToolsets().put("bad/toolset", new ToolSet());

        ConfigPostProcessor.processStructural(config);

        assertEquals(1, config.getModels().size());
        assertNotNull(config.getModels().get("good-model"));
        assertEquals(1, config.getApplications().size());
        assertNotNull(config.getApplications().get("good-app"));
        assertEquals(1, config.getInterceptors().size());
        assertEquals(1, config.getRoles().size());
        assertEquals(1, config.getRoutes().size());
        assertEquals(1, config.getToolsets().size());
    }

    @Test
    void testSemanticAbortThrowsOnDuplicateDeploymentId() {
        Config config = newMutableConfig();
        config.getModels().put("shared", new Model());
        config.getApplications().put("shared", new Application());

        assertThrows(IllegalStateException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
    }

    @Test
    void testSemanticSkipRoutesDuplicateToCallback() {
        Config config = newMutableConfig();
        config.getModels().put("shared", new Model());
        config.getApplications().put("shared", new Application());

        AtomicReference<ResourceTypes> capturedType = new AtomicReference<>();
        AtomicReference<String> capturedKey = new AtomicReference<>();

        ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), (type, error) -> {
            capturedType.set(type);
            capturedKey.set(error.getMapKey());
        });

        // Models processed first; application 'shared' is the duplicate that gets skipped.
        assertEquals(ResourceTypes.APPLICATION, capturedType.get());
        assertEquals("shared", capturedKey.get());
        assertTrue(config.getModels().containsKey("shared"));
        assertFalse(config.getApplications().containsKey("shared"));
    }

    @Test
    void testStripsUnsupportedInterfacesFromApplicationsAndInterceptors() {
        Config config = newMutableConfig();

        Application app = new Application();
        Map<String, DeploymentInterface> appInterfaces = new LinkedHashMap<>();
        appInterfaces.put("openaiChatCompletions", new DeploymentInterface("http://app"));
        appInterfaces.put("openaiResponses", new DeploymentInterface("http://app-responses"));
        app.setInterfaces(appInterfaces);
        config.getApplications().put("app", app);

        Interceptor interceptor = new Interceptor();
        Map<String, DeploymentInterface> interceptorInterfaces = new LinkedHashMap<>();
        interceptorInterfaces.put("openaiChatCompletions", new DeploymentInterface("http://interceptor"));
        interceptorInterfaces.put("anthropicMessages", new DeploymentInterface("http://interceptor-anthropic"));
        interceptor.setInterfaces(interceptorInterfaces);
        config.getInterceptors().put("interceptor", interceptor);

        ConfigPostProcessor.process(config, null);

        // applications and interceptors keep only the chat completions interface
        assertEquals(Set.of("openaiChatCompletions"),
                config.getApplications().get("app").getInterfaces().keySet());
        assertEquals(Set.of("openaiChatCompletions"),
                config.getInterceptors().get("interceptor").getInterfaces().keySet());
    }

    @Test
    void testKeepsAllInterfacesForModels() {
        Config config = newMutableConfig();

        Model model = new Model();
        Map<String, DeploymentInterface> interfaces = new LinkedHashMap<>();
        interfaces.put("openaiChatCompletions", new DeploymentInterface("http://model"));
        interfaces.put("openaiResponses", new DeploymentInterface("http://model-responses"));
        // forward-compatible unknown key is tolerated for models
        interfaces.put("anthropicMessages", new DeploymentInterface("http://model-anthropic"));
        model.setInterfaces(interfaces);
        config.getModels().put("model", model);

        ConfigPostProcessor.process(config, null);

        assertEquals(3, config.getModels().get("model").getInterfaces().size());
    }

    private static Config newMutableConfig() {
        Config config = new Config();
        config.setModels(new HashMap<>());
        config.setApplications(new HashMap<>());
        config.setInterceptors(new HashMap<>());
        config.setToolsets(new LinkedHashMap<>());
        return config;
    }

    @Test
    void testProcessOrchestratesStructuralThenSemanticInAbortMode() {
        Config config = new Config();
        // Use a LinkedHashMap to keep deterministic iteration: bad keys must be removed structurally
        // before the semantic pass complains about anything else.
        Map<String, Model> models = new LinkedHashMap<>();
        models.put("bad/key", new Model());
        models.put("good", new Model());
        config.setModels(models);

        ConfigPostProcessor.process(config, null);

        assertEquals(List.of("good"), List.copyOf(config.getModels().keySet()));
    }
}
