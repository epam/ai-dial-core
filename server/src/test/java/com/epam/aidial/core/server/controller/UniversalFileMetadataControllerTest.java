package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.service.resource.ComplexResourceService;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.data.FileMetadata;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import io.vertx.core.Future;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UniversalFileMetadataControllerTest {
    private Proxy proxy;
    private ProxyContext context;
    private HttpServerRequest request;
    private AccessService accessService;
    private ComplexResourceService service;
    private ResourceDescriptor root;

    @BeforeEach
    void init() {
        proxy = mock(Proxy.class);
        request = mock(HttpServerRequest.class);
        accessService = mock(AccessService.class);
        service = mock(ComplexResourceService.class);
        context = mock(ProxyContext.class, invocation -> invocation.getMethod().getReturnType() == Future.class
                ? Future.succeededFuture() : RETURNS_DEFAULTS.answer(invocation));
        when(context.getRequest()).thenReturn(request);
        when(proxy.getAccessService()).thenReturn(accessService);
        when(proxy.getComplexResourceService()).thenReturn(service);
        when(request.getParam(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        root = ResourceDescriptorFactory.fromPublicUrl("skills/public/s");
        when(accessService.lookupPermissions(any(Set.class), eq(context))).thenReturn(Map.of(root, Set.of(ResourceAccessType.READ)));
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        when(proxy.getTaskExecutor()).thenReturn(executor);
        doAnswer(invocation -> {
            try {
                return Future.succeededFuture(((Callable<?>) invocation.getArgument(0)).call());
            } catch (Exception e) {
                return Future.failedFuture(e);
            }
        }).when(executor).submit(any());
    }

    @Test
    void testInvalidRoot() {
        controller("a.txt").handle("skills/not-a-valid-bucket/s");
        verify(context).respond(eq(HttpStatus.BAD_REQUEST), anyString());
        verify(accessService, never()).lookupPermissions(any(Set.class), any());
    }

    @Test
    void testAuthorizationBeforeExistence() {
        when(accessService.lookupPermissions(any(Set.class), eq(context))).thenReturn(Map.of(root, Set.of()));
        controller("missing.txt").handle(root.getUrl());
        assertResponseFailure(HttpStatus.FORBIDDEN);
        verify(service, never()).getFileMetadata(any(), anyString(), any(), anyInt(), anyBoolean());
    }

    @Test
    void testHiddenPathAfterAuthorization() {
        controller("refs/.dial-resource").handle(root.getUrl());
        assertResponseFailure(HttpStatus.NOT_FOUND);
        verify(accessService).lookupPermissions(Set.of(root), context);
        verify(service, never()).getFileMetadata(any(), anyString(), any(), anyInt(), anyBoolean());
    }

    @Test
    void testInvalidRelativePath() {
        controller("refs/%2Fsecret").handle(root.getUrl());
        verify(context).respond(eq(HttpStatus.BAD_REQUEST), anyString());
    }

    @Test
    void testMissingMetadata() {
        controller("missing.txt").handle(root.getUrl());
        verify(context).respond(HttpStatus.NOT_FOUND);
    }

    @Test
    void testRootPermissionsAppliedToAllNodes() {
        Set<ResourceAccessType> permissions = Set.of(ResourceAccessType.READ, ResourceAccessType.WRITE, ResourceAccessType.SHARE);
        when(accessService.lookupPermissions(any(Set.class), eq(context))).thenReturn(Map.of(root, permissions));
        when(context.getBooleanRequestQueryParam("permissions")).thenReturn(true);
        FileMetadata file = new FileMetadata(ResourceDescriptorFactory.fromPublicUrl("skills/public/s/files/refs/a.txt"), 1L, "text/plain");
        ResourceFolderMetadata nested = new ResourceFolderMetadata(ResourceDescriptorFactory.fromPublicUrl("skills/public/s/files/refs/"), List.of(file), null);
        ResourceFolderMetadata folder = new ResourceFolderMetadata(ResourceDescriptorFactory.fromPublicUrl("skills/public/s/files/"), List.of(nested), null);
        when(service.getFileMetadata(eq(root), eq(""), isNull(), eq(100), eq(false))).thenReturn(folder);
        controller(null).handle(root.getUrl());
        assertEquals(permissions, folder.getPermissions());
        assertEquals(permissions, nested.getPermissions());
        assertEquals(permissions, file.getPermissions());
        verify(accessService, times(1)).lookupPermissions(Set.of(root), context);
        verify(context).respond(HttpStatus.OK, "application/json", folder);
    }

    @Test
    void testMetadataAcceptNegotiation() {
        FileMetadata file = new FileMetadata(ResourceDescriptorFactory.fromPublicUrl("skills/public/s/files/a.txt"), 1L, "text/plain");
        when(service.getFileMetadata(eq(root), eq("a.txt"), isNull(), eq(100), eq(false))).thenReturn(file);
        when(request.getHeader(HttpHeaders.ACCEPT)).thenReturn(MetadataBase.MIME_TYPE);
        controller("a.txt").handle(root.getUrl());
        verify(context).respond(HttpStatus.OK, MetadataBase.MIME_TYPE, file);
    }

    @Test
    void testInvalidLimit() {
        when(request.getParam("limit", "100")).thenReturn("-1");
        controller("refs/").handle(root.getUrl());
        verify(context).respond(HttpStatus.BAD_REQUEST, "Limit is out of allowed range: [0, 1000]");
        verify(service, never()).getFileMetadata(any(), anyString(), any(), anyInt(), anyBoolean());
    }

    @Test
    void testQueryParametersPassedThrough() {
        when(request.getParam("token")).thenReturn("opaque");
        when(request.getParam("limit", "100")).thenReturn("0");
        when(request.getParam("recursive", "false")).thenReturn("true");
        controller("refs/").handle(root.getUrl());
        verify(service).getFileMetadata(root, "refs/", "opaque", 0, true);
    }

    @Test
    void testServiceFailurePropagates() {
        IllegalStateException failure = new IllegalStateException("storage failed");
        when(service.getFileMetadata(eq(root), eq("a.txt"), isNull(), eq(100), eq(false))).thenThrow(failure);
        controller("a.txt").handle(root.getUrl());
        verify(context).respond(eq(failure), anyString());
    }

    private UniversalFileMetadataController controller(String path) {
        return new UniversalFileMetadataController(proxy, context, path);
    }

    private void assertResponseFailure(HttpStatus status) {
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(context).respond(error.capture(), anyString());
        assertEquals(status, ((HttpException) error.getValue()).getStatus());
    }
}
