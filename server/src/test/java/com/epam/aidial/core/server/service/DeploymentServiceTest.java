package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Alias;
import com.epam.aidial.core.config.AliasPresentation;
import com.epam.aidial.core.config.AliasTarget;
import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.alias.AliasService;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class DeploymentServiceTest {

    @Mock
    private EncryptionService encryptionService;

    @Mock
    private ApplicationService applicationService;

    @Mock
    private AccessService accessService;

    @Mock
    private ProxyContext context;

    @Mock
    private Config config;

    @Mock
    private ApplicationSchemaService applicationSchemaService;

    @Mock
    private AliasService aliasService;

    @InjectMocks
    private DeploymentService service;

    @BeforeEach
    public void beforeEach() {
        when(context.getConfig()).thenReturn(config);
    }

    @Test
    public void testFindDeployment_WhenDeploymentExistsInConfig() {
        Application application = new Application();
        when(config.selectDeployment("id")).thenReturn(application);

        Deployment deployment = service.findDeployment(context, "id");
        assertEquals(application, deployment);
    }

    @Test
    public void testFindDeployment_WhenDeploymentExistsInConfigButItNotPermitted() {
        Application application = new Application();
        application.setUserRoles(Set.of("power-user"));
        when(config.selectDeployment("id")).thenReturn(application);
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());

        assertThrows(PermissionDeniedException.class, () -> service.findDeployment(context, "id"));
    }

    @Test
    public void testFindDeployment_WhenCustomAppHasInvalidPath() {

        assertThrows(ResourceNotFoundException.class, () -> service.findDeployment(context, "my/application/id"));
    }

    @Test
    public void testFindDeployment_WhenCustomAppIsFolder() {

        assertThrows(ResourceNotFoundException.class, () -> service.findDeployment(context, "applications/public/my-app/"));
    }

    @Test
    public void testFindDeployment_WhenAccessDenied() {

        assertThrows(PermissionDeniedException.class, () -> service.findDeployment(context, "files/public/my-app"));
    }

    @Test
    public void testFindDeployment_WhenCustomAppHasNoAccess() {
        when(accessService.hasReadAccess(any(ResourceDescriptor.class), eq(context))).thenReturn(false);
        assertThrows(PermissionDeniedException.class, () -> service.findDeployment(context, "applications/public/my-app"));
    }

    @Test
    public void testFindDeployment_Success() {
        when(accessService.hasReadAccess(any(ResourceDescriptor.class), eq(context))).thenReturn(true);
        Application application = new Application();
        when(applicationService.getApplication(any(ResourceDescriptor.class))).thenReturn(Pair.of(new ResourceItemMetadata(), application));

        Deployment deployment = service.findDeployment(context, "applications/public/my-app");

        assertEquals(application, deployment);
    }

    /**
     * The access {@link AliasService#hasConfigAccess} answers for an alias with {@code userRoles}; null, decided by the
     * target, for one without.
     */
    private void stubConfigAccess() {
        when(aliasService.hasConfigAccess(eq(context), any())).thenAnswer(call -> {
            Alias alias = call.getArgument(1);
            return alias.getUserRoles() == null ? null : alias.hasAccess(context.getUserRoles());
        });
    }

    @Test
    public void storedAliasIsFoundByItsUrlAndAllowedByItsUserRoles() {
        stubConfigAccess();
        Alias alias = storedAlias("pub-v1");
        alias.setUserRoles(Set.of("default-user"));
        when(aliasService.findStoredAlias(any())).thenReturn(alias);

        when(context.getUserRoles()).thenReturn(List.of("default-user"));
        assertSame(alias, service.findDeployment(context, "aliases/public/pub-v1"));

        when(context.getUserRoles()).thenReturn(List.of("power-user"));
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        assertThrows(PermissionDeniedException.class, () -> service.findDeployment(context, "aliases/public/pub-v1"));
    }

    @Test
    public void missingStoredAliasAnswersNotFound() {
        assertThrows(ResourceNotFoundException.class, () -> service.findDeployment(context, "aliases/public/pub-v1"));
    }

    @Test
    public void aliasesOutsideThePublicAliasFolderAreNotFound() {
        assertThrows(ResourceNotFoundException.class, () -> service.findDeployment(context, "aliases/public/folder/pub-v1"));
        verifyNoInteractions(accessService, aliasService);
    }

    @Test
    public void shortNameOfStoredAliasDoesNotReachIt() {
        assertThrows(ResourceNotFoundException.class, () -> service.findDeployment(context, "pub-v1"));
        verifyNoInteractions(aliasService);
    }

    @Test
    public void fileAliasIsUsableByItsUserRoles() {
        stubConfigAccess();
        Alias alias = new Alias();
        alias.setName("assistant");
        alias.setUserRoles(Set.of("power-user"));
        when(config.selectDeployment("assistant")).thenReturn(alias);
        when(context.getUserRoles()).thenReturn(List.of("default-user"));
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());

        assertThrows(PermissionDeniedException.class, () -> service.findDeployment(context, "assistant"));
        verifyNoInteractions(accessService);
    }

    @Test
    public void aliasListingKeepsTheAliasesOfTheCallerRoles() {
        stubConfigAccess();
        Alias allowed = storedAlias("allowed");
        allowed.setUserRoles(Set.of("default-user"));
        Alias denied = storedAlias("denied");
        denied.setUserRoles(Set.of("power-user"));
        Alias open = storedAlias("open");
        Alias file = new Alias();
        file.setName("file");
        file.setTarget(new AliasTarget().setPath("models/gpt-4"));
        Map<String, Alias> aliases = new LinkedHashMap<>();
        aliases.put("file", file);
        when(config.getAliases()).thenReturn(aliases);
        when(aliasService.listStoredAliases()).thenReturn(List.of(allowed, denied, open));
        when(context.getUserRoles()).thenReturn(List.of("default-user"));
        when(aliasService.toView(eq(context), any())).thenAnswer(call -> {
            Model view = new Model();
            view.setName(call.<Alias>getArgument(1).getName());
            return view;
        });

        List<String> names = service.listAliasViews(context, AliasPresentation.MODEL).stream().map(Deployment::getName).toList();

        assertEquals(List.of("file", "aliases/public/allowed", "aliases/public/open"), names);
        verifyNoInteractions(accessService);
    }

    @Test
    public void aliasListingWithoutStorageShowsTheConfigAliases() {
        stubConfigAccess();
        Alias file = new Alias();
        file.setName("file");
        file.setTarget(new AliasTarget().setPath("models/gpt-4"));
        when(config.getAliases()).thenReturn(Map.of("file", file));
        when(aliasService.listStoredAliases()).thenThrow(new IllegalStateException("storage is down"));
        when(aliasService.toView(eq(context), any())).thenAnswer(call -> {
            Model view = new Model();
            view.setName(call.<Alias>getArgument(1).getName());
            return view;
        });

        List<String> names = service.listAliasViews(context, AliasPresentation.MODEL).stream().map(Deployment::getName).toList();

        assertEquals(List.of("file"), names);
    }

    @Test
    public void storedAliasesAreListedOncePerRequest() {
        when(config.getAliases()).thenReturn(Map.of());
        when(aliasService.listStoredAliases()).thenReturn(List.of(storedAlias("a")));
        Supplier<List<Alias>> stored = service.storedAliasesOnce();

        // as /v1/deployments lists models, applications and toolsets in one request
        service.listAliasViews(context, AliasPresentation.MODEL, stored);
        service.listAliasViews(context, AliasPresentation.APPLICATION, stored);
        service.listAliasViews(context, AliasPresentation.TOOLSET, stored);

        verify(aliasService, times(1)).listStoredAliases();
    }

    private static Alias storedAlias(String name) {
        Alias alias = new Alias();
        alias.setName("aliases/public/" + name);
        alias.setTarget(new AliasTarget().setPath("models/gpt-4"));
        return alias;
    }

    @Test
    public void testGetInterceptors() {
        when(config.getGlobalInterceptors()).thenReturn(List.of("i1", "i2"));
        Application application = new Application();
        when(applicationSchemaService.getInterceptors(application)).thenReturn(List.of("i3", "i2"));
        application.setInterceptors(List.of("i4", "i3"));

        List<String> result = service.getInterceptors(context, application);

        assertEquals(List.of("i1", "i2", "i3", "i4"), result);
    }
}
