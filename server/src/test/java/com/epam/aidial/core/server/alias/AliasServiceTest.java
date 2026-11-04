package com.epam.aidial.core.server.alias;

import com.epam.aidial.core.config.Alias;
import com.epam.aidial.core.config.AliasPresentation;
import com.epam.aidial.core.config.AliasTarget;
import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.LocalizedValue;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.service.ApplicationSchemaService;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.service.ToolSetService;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AliasServiceTest {

    private static final String SELF = "http://localhost:8080";

    private final ApplicationService applicationService = mock(ApplicationService.class);
    private final ToolSetService toolSetService = mock(ToolSetService.class);
    private final ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
    private final ProxyContext context = mock(ProxyContext.class);
    private final AliasService service = new AliasService(applicationService, toolSetService, applicationSchemaService, null);

    @BeforeEach
    void setUp() {
        Model model = new Model();
        model.setName("gpt-4");
        model.setEndpoint("http://origin/chat");
        model.setDisplayName(LocalizedValue.of("GPT-4"));
        Config config = new Config();
        config.setModels(Map.of("gpt-4", model));
        when(context.getConfig()).thenReturn(config);
        when(applicationSchemaService.modifyEndpointsForCustomApplication(any())).thenAnswer(call -> call.getArgument(0));
        service.setLocalBaseUrl(SELF);
    }

    @Test
    void configTargetIsCalledByItsName() {
        AliasPass pass = (AliasPass) service.toPass(context, alias(null, "models/gpt-4"));

        assertEquals("gpt-4", pass.getTargetName());
        assertFalse(pass.isResourceTarget());
        assertInstanceOf(AliasApplicationPass.class, pass);
    }

    @Test
    void missingOrUnfittingTargetAnswers503() {
        HttpException missing = assertThrows(HttpException.class, () -> service.toPass(context, alias(null, "models/gone")));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, missing.getStatus());

        HttpException unfitting = assertThrows(HttpException.class,
                () -> service.toPass(context, alias(AliasPresentation.TOOLSET, "models/gpt-4")));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unfitting.getStatus());
    }

    @Test
    void resourceTargetIsReadAndCalledByItsUrl() {
        Application application = new Application();
        application.setName("applications/public/app");
        application.setEndpoint("http://app/chat");
        when(applicationService.getApplication(any())).thenReturn(Pair.of(null, application));

        AliasPass pass = (AliasPass) service.toPass(context, alias(null, "applications/public/app"));

        assertEquals("applications/public/app", pass.getTargetName());
        assertTrue(pass.isResourceTarget());
    }

    @Test
    void toolsetResourceTargetServesToolsetAlias() {
        ToolSet toolSet = new ToolSet();
        toolSet.setEndpoint("http://tools/mcp");
        when(toolSetService.getToolSet(any())).thenReturn(Pair.of(null, toolSet));

        assertInstanceOf(AliasToolSetPass.class, service.toPass(context, alias(null, "toolsets/public/search")));
    }

    @Test
    void schemaBasedApplicationTakesItsMcpFromTheSchema() {
        Application application = new Application();
        application.setName("applications/public/app");
        application.setApplicationTypeSchemaId(URI.create("https://schemas/app"));
        when(applicationService.getApplication(any())).thenReturn(Pair.of(null, application));
        Application.Mcp mcp = new Application.Mcp();
        mcp.setEndpoint("http://app/mcp");
        when(applicationSchemaService.getMcp(application)).thenReturn(mcp);

        AliasToolSetPass pass = (AliasToolSetPass) service.toPass(context, alias(AliasPresentation.TOOLSET, "applications/public/app"));

        assertEquals("http://localhost:8080/v1/deployments/applications/public/app/mcp", pass.getEndpoint());
        assertNull(application.getMcp());
    }

    @Test
    void missingResourceTargetHasNoView() {
        when(applicationService.getApplication(any())).thenThrow(new ResourceNotFoundException("gone"));

        assertNull(service.toView(context, alias(null, "applications/public/app")));
    }

    @Test
    void inMemoryViewLeavesResourceTargetsOutWithoutReadingThem() {
        assertNull(service.toView(context, alias(null, "applications/public/app"), true));
        verifyNoInteractions(applicationService);
    }

    @Test
    void viewTakesTheTargetDisplayName() {
        Model view = (Model) service.toView(context, alias(null, "models/gpt-4"), true);

        assertEquals("assistant-v1", view.getName());
        assertEquals(LocalizedValue.of("GPT-4"), view.getDisplayName());
    }

    private static Alias alias(AliasPresentation presentation, String path) {
        Alias alias = new Alias();
        alias.setName("assistant-v1");
        alias.setPresentAs(presentation);
        alias.setTarget(new AliasTarget().setPath(path));
        return alias;
    }
}
