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
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.service.ApplicationSchemaService;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.service.ToolSetService;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final AccessService accessService = mock(AccessService.class);
    private final ProxyContext context = mock(ProxyContext.class);
    private final ResourceService resourceService = mock(ResourceService.class);
    private final AliasService service = new AliasService(applicationService, toolSetService, applicationSchemaService, null,
            resourceService, accessService);

    @BeforeEach
    void setUp() {
        Model model = new Model();
        model.setName("gpt-4");
        model.setEndpoint("http://origin/chat");
        model.setDisplayName(LocalizedValue.of("GPT-4"));
        model.setUserRoles(Set.of("power-user"));
        Config config = new Config();
        config.setModels(Map.of("gpt-4", model));
        when(context.getConfig()).thenReturn(config);
        when(context.getUserRoles()).thenReturn(List.of("power-user"));
        when(accessService.hasReadAccess(any(), any())).thenReturn(true);
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
    void findTargetResolvesConfigAndResourceTargets() {
        Config config = context.getConfig();
        assertEquals("gpt-4", service.findTarget(config, alias(null, "models/gpt-4")).getName());
        assertNull(service.findTarget(config, alias(null, "models/gone")));
        verifyNoInteractions(applicationService);

        when(applicationService.getApplication(any())).thenThrow(new ResourceNotFoundException("gone"));
        assertNull(service.findTarget(config, alias(null, "applications/public/app")));
    }

    @Test
    void viewTakesTheTargetDisplayName() {
        Model view = (Model) service.toView(context, alias(null, "models/gpt-4"));

        assertEquals("assistant-v1", view.getName());
        assertEquals(LocalizedValue.of("GPT-4"), view.getDisplayName());
    }

    @Test
    void aliasWithoutUserRolesTakesTheConfigTargetRoles() {
        Alias alias = alias(null, "models/gpt-4");
        assertEquals(Boolean.TRUE, service.hasConfigAccess(context, alias));
        assertEquals(Set.of("power-user"), service.toView(context, alias).getUserRoles());

        when(context.getUserRoles()).thenReturn(List.of("default-user"));
        assertEquals(Boolean.FALSE, service.hasConfigAccess(context, alias));
        HttpException forbidden = assertThrows(HttpException.class, () -> service.toPass(context, alias));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatus());

        alias.setUserRoles(Set.of("default-user"));
        assertEquals(Boolean.TRUE, service.hasConfigAccess(context, alias));
        assertEquals(Set.of("default-user"), service.toView(context, alias).getUserRoles());
    }

    @Test
    void aliasWithoutUserRolesTakesReadAccessToTheResourceTarget() {
        Application application = new Application();
        application.setName("applications/public/app");
        application.setEndpoint("http://app/chat");
        when(applicationService.getApplication(any())).thenReturn(Pair.of(null, application));
        Alias alias = alias(null, "applications/public/app");
        assertNull(service.hasConfigAccess(context, alias));

        when(accessService.hasReadAccess(any(), any())).thenReturn(false);
        HttpException forbidden = assertThrows(HttpException.class, () -> service.toPass(context, alias));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatus());
        assertNull(service.toView(context, alias));

        alias.setUserRoles(Set.of("power-user"));
        assertInstanceOf(AliasPass.class, service.toPass(context, alias));
    }

    @Test
    void securedTargetIsTheToolsetUnderItsId() {
        ToolSet toolSet = new ToolSet();
        toolSet.setEndpoint("http://tools/mcp");
        when(toolSetService.getToolSet(any())).thenReturn(Pair.of(null, toolSet));

        AliasService.SecuredTarget target = service.toSecuredTarget(context, alias(null, "toolsets/public/my%20search"));

        assertEquals("toolsets/public/my search", target.id());
        assertTrue(target.resource());
        assertEquals("toolsets/public/my search", service.securedTargetId(alias(null, "toolsets/public/my%20search")));
        assertThrows(ResourceNotFoundException.class, () -> service.toSecuredTarget(context, alias(null, "models/gpt-4")));
    }

    @Test
    void checkTargetReadsTheResourceTarget() {
        when(applicationService.getApplication(any())).thenThrow(new ResourceNotFoundException("gone"));
        assertEquals(AliasService.TargetProblem.MISSING, service.checkTarget(context, alias(null, "applications/public/app")));

        ToolSet toolSet = new ToolSet();
        when(toolSetService.getToolSet(any())).thenReturn(Pair.of(null, toolSet));
        assertEquals(AliasService.TargetProblem.INCOMPATIBLE,
                service.checkTarget(context, alias(AliasPresentation.MODEL, "toolsets/public/search")));
        assertNull(service.checkTarget(context, alias(null, "toolsets/public/search")));
    }

    @Test
    void storedAliasIsReadOnDemandAndNamedByItsUrl() {
        ResourceDescriptor resource = ResourceDescriptorFactory.fromAnyUrl("aliases/public/pub-v1", null);
        when(resourceService.getResource(resource))
                .thenReturn("{\"userRoles\":[\"default-user\"],\"target\":{\"path\":\"models/gpt-4\"}}");

        Alias stored = service.findStoredAlias(resource);
        when(context.getUserRoles()).thenReturn(List.of("default-user"));

        assertEquals("aliases/public/pub-v1", stored.getName());
        assertEquals(Set.of("default-user"), stored.getUserRoles());
        assertEquals("aliases/public/pub-v1", ((AliasPass) service.toPass(context, stored)).getAlias().getName());
    }

    @Test
    void onlyItemsDirectlyInThePublicAliasFolderAreStoredAliases() {
        assertNull(service.findStoredAlias(ResourceDescriptorFactory.fromAnyUrl("aliases/public/folder/pub-v1", null)));
        assertNull(service.findStoredAlias(ResourceDescriptorFactory.fromAnyUrl("aliases/public/missing", null)));
        ResourceDescriptor broken = ResourceDescriptorFactory.fromAnyUrl("aliases/public/broken", null);
        when(resourceService.getResource(broken)).thenReturn("{\"displayName\":\"no target\"}");
        assertNull(service.findStoredAlias(broken));
    }

    @Test
    void storedAliasesAreListedFlat() {
        ResourceDescriptor flat = ResourceDescriptorFactory.fromAnyUrl("aliases/public/pub-v1", null);
        ResourceDescriptor nested = ResourceDescriptorFactory.fromAnyUrl("aliases/public/folder/pub-v2", null);
        String body = "{\"target\":{\"path\":\"models/gpt-4\"}}";
        when(resourceService.listResources(any(), any())).thenReturn(List.of(
                Pair.of(new ResourceItemMetadata(flat), body), Pair.of(new ResourceItemMetadata(nested), body)));

        assertEquals(List.of("aliases/public/pub-v1"), service.listStoredAliases().stream().map(Alias::getName).toList());
    }

    private static Alias alias(AliasPresentation presentation, String path) {
        Alias alias = new Alias();
        alias.setName("assistant-v1");
        alias.setPresentAs(presentation);
        alias.setTarget(new AliasTarget().setPath(path));
        return alias;
    }
}
