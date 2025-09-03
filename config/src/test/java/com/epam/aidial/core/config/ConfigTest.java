package com.epam.aidial.core.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        Assistants assistants = new Assistants();
        Assistant assistant = new Assistant();
        assistants.setAssistants(Map.of("assist", assistant));
        config.setAssistant(assistants);

        assertEquals(app, config.selectDeployment("app"));
        assertEquals(model, config.selectDeployment("model"));
        assertEquals(toolSet, config.selectDeployment("toolset"));
        assertEquals(interceptor, config.selectDeployment("interceptor"));
        assertEquals(assistant, config.selectDeployment("assist"));
        assertNull(config.selectDeployment("unknown"));
    }
}
