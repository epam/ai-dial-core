package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.config.ResourceDependency;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.function.request.ChatCompletionRequest;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.ConsentService;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every branch of request-start resolution: the declaration is a request, not a grant — reach is
 * the originating user's own permissions, consent is the content-bound admin record, and the
 * passing intersection is baked into the per-request key the application will hold.
 */
@ExtendWith(MockitoExtension.class)
public class ResolveResourceDependenciesFnTest {

    private static final String USER_BUCKET = "encrypted-user-bucket";

    @Mock
    private Proxy proxy;

    @Mock
    private ProxyContext context;

    @Mock
    private AccessService accessService;

    @Mock
    private ConsentService consentService;

    @Mock
    private EncryptionService encryptionService;

    @InjectMocks
    private ResolveResourceDependenciesFn fn;

    private final ApiKeyData proxyApiKeyData = new ApiKeyData();
    private Application application;
    private RequestObject request;

    @BeforeEach
    void setUp() {
        application = new Application();
        application.setName("app");
        request = new ChatCompletionRequest(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());

        // The resolution window: the context still carries the originating user (no per-request key),
        // and the proxy key data the grants are baked into.
        lenient().when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        lenient().when(context.getProxyApiKeyData()).thenReturn(proxyApiKeyData);
        lenient().when(context.getUserId()).thenReturn("user-sub");
        lenient().when(context.getProxy()).thenReturn(proxy);
        lenient().when(proxy.getAccessService()).thenReturn(accessService);
        lenient().when(proxy.getConsentService()).thenReturn(consentService);
        lenient().when(proxy.getEncryptionService()).thenReturn(encryptionService);
        lenient().when(encryptionService.encrypt(anyString())).thenReturn(USER_BUCKET);
    }

    private static ResourceDependency dependency(String path, boolean required) {
        return new ResourceDependency()
                .setKind(ResourceDependency.KIND)
                .setLinkId("lnk_1")
                .setTarget(new ResourceDependency.Target().setPath(path))
                .setAccess(Set.of(ResourceAccessType.READ, ResourceAccessType.WRITE))
                .setRequired(required);
    }

    @Test
    void apply_isNoOpForNonApplicationDeployments() {
        // Interceptor hop or a plain model/toolset call — dependencies resolve for the application
        // being called, nothing else.
        when(context.getDeployment()).thenReturn(mock(Deployment.class));

        assertFalse(fn.apply(request));
        verify(proxy, never()).getConsentService();
        verify(context, never()).getProxyApiKeyData();
    }

    @Test
    void apply_isNoOpWithoutDeclaration() {
        when(context.getDeployment()).thenReturn(application);

        assertFalse(fn.apply(request));
        verify(consentService, never()).isAdminConsented(anyString(), any());
    }

    @Test
    void apply_skipsWhenNotTheRootUserCall() {
        // Load-bearing timing: under a per-request key the reach checks would evaluate a
        // deployment's own key instead of the originating user — so hops that arrive with one
        // (an interceptor's final call back, a chained app-to-app call) are skipped, never run
        // and never thrown: a declaring app behind an interceptor must stay callable.
        when(context.getDeployment()).thenReturn(application);
        application.setResourceDependencies(List.of(dependency("current-user/skills/", false)));
        ApiKeyData assigned = new ApiKeyData();
        assigned.setPerRequestKey("prk");
        when(context.getApiKeyData()).thenReturn(assigned);

        assertFalse(fn.apply(request));
        verify(consentService, never()).isAdminConsented(anyString(), any());
        assertTrue(proxyApiKeyData.getPerRequestSharedResources().isEmpty());
    }

    @Test
    void apply_bakesGrantForConsentedReachablePlaceholderTarget() {
        when(context.getDeployment()).thenReturn(application);
        application.setResourceDependencies(List.of(dependency("current-user/skills/", false)));
        when(consentService.isAdminConsented(eq("app"), any())).thenReturn(true);
        when(accessService.lookupPermissions(any(), eq(context)))
                .thenReturn(Map.of(userSkillsFolder(), Set.of(ResourceAccessType.READ, ResourceAccessType.WRITE)));

        assertFalse(fn.apply(request));

        // The grant lands in the key's own shared map — the app's direct calls with this key are
        // served by it; the target is the user's skills ROOT folder, so the grant prefix-matches
        // everything under it (findFolderPermissions).
        assertEquals(Set.of(ResourceAccessType.READ, ResourceAccessType.WRITE),
                proxyApiKeyData.getPerRequestSharedResources().get(userSkillsFolder().getUrl()).permissions());
    }

    @Test
    void apply_combinesGrantsForRecordsWithTheSameTarget() {
        // Two records targeting the same URL with different rights combine, never overwrite.
        when(context.getDeployment()).thenReturn(application);
        application.setResourceDependencies(List.of(
                new ResourceDependency().setKind(ResourceDependency.KIND).setLinkId("lnk_w")
                        .setTarget(new ResourceDependency.Target().setPath("files/public/p/"))
                        .setAccess(Set.of(ResourceAccessType.WRITE)),
                new ResourceDependency().setKind(ResourceDependency.KIND).setLinkId("lnk_r")
                        .setTarget(new ResourceDependency.Target().setPath("files/public/p/"))
                        .setAccess(Set.of(ResourceAccessType.READ))));
        when(consentService.isAdminConsented(eq("app"), any())).thenReturn(true);
        ResourceDescriptor target = com.epam.aidial.core.server.util.ResourceDescriptorFactory
                .fromAnyUrl("files/public/p/", encryptionService);
        when(accessService.lookupPermissions(any(), eq(context)))
                .thenReturn(Map.of(target, Set.of(ResourceAccessType.READ, ResourceAccessType.WRITE)));

        assertFalse(fn.apply(request));

        assertEquals(Set.of(ResourceAccessType.READ, ResourceAccessType.WRITE),
                proxyApiKeyData.getPerRequestSharedResources().get("files/public/p/").permissions());
    }

    @Test
    void apply_neverResolvesInternalEngineTypesAsPersonalTargets() {
        // ResourceTypes.of() maps internal engine types (credentials, keys, models…) — a
        // current-user/credentials/ declaration must never reach the user's secret-bearing blobs,
        // whoever authored the app (config-file apps bypass the write-time ceiling).
        when(context.getDeployment()).thenReturn(application);
        application.setResourceDependencies(List.of(
                dependency("current-user/credentials/", false),
                dependency("current-user/keys/", false)));
        when(consentService.isAdminConsented(eq("app"), any())).thenReturn(true);

        assertFalse(fn.apply(request));
        verify(accessService, never()).lookupPermissions(any(), any());
        assertTrue(proxyApiKeyData.getPerRequestSharedResources().isEmpty());
    }

    @Test
    void apply_bakesGrantForConcretePublicFolderTarget() {
        when(context.getDeployment()).thenReturn(application);
        application.setResourceDependencies(List.of(dependency("files/public/policies/", false)));
        when(consentService.isAdminConsented(eq("app"), any())).thenReturn(true);
        ResourceDescriptor target = com.epam.aidial.core.server.util.ResourceDescriptorFactory
                .fromAnyUrl("files/public/policies/", encryptionService);
        when(accessService.lookupPermissions(any(), eq(context)))
                .thenReturn(Map.of(target, Set.of(ResourceAccessType.READ, ResourceAccessType.WRITE)));

        assertFalse(fn.apply(request));

        assertEquals(Set.of(ResourceAccessType.READ, ResourceAccessType.WRITE),
                proxyApiKeyData.getPerRequestSharedResources().get("files/public/policies/").permissions());
    }

    @Test
    void apply_skipsUnreachableTargetWithoutFailure() {
        // Fail closed per record: the user cannot reach the target — no grant, no failure.
        when(context.getDeployment()).thenReturn(application);
        application.setResourceDependencies(List.of(dependency("files/public/policies/", false)));
        when(consentService.isAdminConsented(eq("app"), any())).thenReturn(true);
        when(accessService.lookupPermissions(any(), eq(context))).thenReturn(Map.of());

        assertFalse(fn.apply(request));
        assertTrue(proxyApiKeyData.getPerRequestReceivers().isEmpty());
    }

    @Test
    void apply_skipsUnconsentedDeclarationWithoutFailure() {
        when(context.getDeployment()).thenReturn(application);
        application.setResourceDependencies(List.of(dependency("files/public/policies/", false)));
        when(consentService.isAdminConsented(eq("app"), any())).thenReturn(false);

        assertFalse(fn.apply(request));
        verify(accessService, never()).lookupPermissions(any(), any());
        assertTrue(proxyApiKeyData.getPerRequestReceivers().isEmpty());
    }

    @Test
    void apply_failsCallWhenRequiredTargetIsUnreachable() {
        when(context.getDeployment()).thenReturn(application);
        application.setResourceDependencies(List.of(dependency("files/public/policies/", true)));
        when(consentService.isAdminConsented(eq("app"), any())).thenReturn(true);
        when(accessService.lookupPermissions(any(), eq(context))).thenReturn(Map.of());

        HttpException error = assertThrows(HttpException.class, () -> fn.apply(request));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        assertTrue(error.getMessage().contains("files/public/policies/"));
    }

    @Test
    void apply_failsCallWhenRequiredDeclarationIsUnconsented() {
        when(context.getDeployment()).thenReturn(application);
        application.setResourceDependencies(List.of(dependency("current-user/skills/", true)));
        when(consentService.isAdminConsented(eq("app"), any())).thenReturn(false);

        HttpException error = assertThrows(HttpException.class, () -> fn.apply(request));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
    }

    @Test
    void apply_treatsMalformedRecordsAsUnresolvable() {
        // Config-file apps bypass write-time validation: a malformed record is unresolvable —
        // skip, or fail the call when required — never a crash and never a grant.
        when(context.getDeployment()).thenReturn(application);
        application.setResourceDependencies(List.of(
                new ResourceDependency().setKind("dial.resource") // wrong kind — still parsed
                        .setLinkId("lnk_bad")
                        .setTarget(new ResourceDependency.Target().setPath("files/public/p/"))
                        .setAccess(Set.of(ResourceAccessType.READ)),
                dependency("buckets/unknown/root/", false), // unknown root — no descriptor resolvable
                new ResourceDependency().setKind(ResourceDependency.KIND)
                        .setLinkId("lnk_share")
                        .setTarget(new ResourceDependency.Target().setPath("files/public/p/"))
                        .setAccess(Set.of(ResourceAccessType.SHARE)))); // SHARE is not a dependency right
        when(consentService.isAdminConsented(eq("app"), any())).thenReturn(true);

        assertFalse(fn.apply(request));
        assertTrue(proxyApiKeyData.getPerRequestReceivers().isEmpty());
        verify(accessService, never()).lookupPermissions(any(), any());
    }

    private ResourceDescriptor userSkillsFolder() {
        return com.epam.aidial.core.server.util.ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.SKILL, USER_BUCKET, "Users/user-sub/", "");
    }
}
