package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.data.NodeType;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ResponseMappingServiceTest {

    private static final long DEFAULT_TTL = 30L * 24 * 60 * 60 * 1000;

    @Mock
    private Supplier<String> generator;

    @Mock
    private ResourceService resourceService;

    @Mock
    private Vertx vertx;

    @Mock
    private AsyncTaskExecutor taskExecutor;

    private ResponseMappingService service;

    @BeforeEach
    void setUp() {
        service = new ResponseMappingService(vertx, generator, resourceService);
    }

    private void triggerCleanup() {
        when(taskExecutor.submit(any())).thenAnswer(inv -> {
            Callable<?> callable = inv.getArgument(0);
            callable.call();
            return Future.succeededFuture();
        });

        ArgumentCaptor<Handler<Long>> handlerCaptor = ArgumentCaptor.captor();
        service.init(taskExecutor);
        verify(vertx).setPeriodic(anyLong(), anyLong(), handlerCaptor.capture());
        handlerCaptor.getValue().handle(0L);
    }

    @Test
    void testCleanup_deletesExpiredItem() {
        long createdAt = System.currentTimeMillis() - DEFAULT_TTL - 1000;

        ResourceItemMetadata folderItem = new ResourceItemMetadata();
        folderItem.setNodeType(NodeType.FOLDER);
        folderItem.setName("deploy1");

        ResourceFolderMetadata rootFolder = new ResourceFolderMetadata();
        rootFolder.setItems(List.of(folderItem));

        ResourceItemMetadata expiredItem = new ResourceItemMetadata();
        expiredItem.setNodeType(NodeType.ITEM);
        expiredItem.setName("uuid-abc");
        expiredItem.setCreatedAt(createdAt);

        ResourceFolderMetadata deployFolder = new ResourceFolderMetadata();
        deployFolder.setItems(List.of(expiredItem));

        when(resourceService.getFolderMetadata(any(), any(), anyInt(), anyBoolean()))
                .thenReturn(rootFolder)
                .thenReturn(deployFolder);

        triggerCleanup();

        ResourceDescriptor expected = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.RESPONSE_MAPPING, "response_mappings", "response_mappings/", "deploy1/uuid-abc");
        verify(resourceService).deleteResource(eq(expected), eq(EtagHeader.ANY));
    }

    @Test
    void testCleanup_skipsNonExpiredItem() {
        long createdAt = System.currentTimeMillis() - DEFAULT_TTL + 60_000;

        ResourceItemMetadata folderItem = new ResourceItemMetadata();
        folderItem.setNodeType(NodeType.FOLDER);
        folderItem.setName("deploy1");

        ResourceFolderMetadata rootFolder = new ResourceFolderMetadata();
        rootFolder.setItems(List.of(folderItem));

        ResourceItemMetadata freshItem = new ResourceItemMetadata();
        freshItem.setNodeType(NodeType.ITEM);
        freshItem.setName("uuid-fresh");
        freshItem.setCreatedAt(createdAt);

        ResourceFolderMetadata deployFolder = new ResourceFolderMetadata();
        deployFolder.setItems(List.of(freshItem));

        when(resourceService.getFolderMetadata(any(), any(), anyInt(), anyBoolean()))
                .thenReturn(rootFolder)
                .thenReturn(deployFolder);

        triggerCleanup();

        verify(resourceService, never()).deleteResource(any(), any());
    }

    @Test
    void testCleanup_nullRootFolderStopsEarly() {
        when(resourceService.getFolderMetadata(any(), any(), anyInt(), anyBoolean()))
                .thenReturn(null);

        triggerCleanup();

        verify(resourceService, never()).deleteResource(any(), any());
    }

    @Test
    void testCleanup_skipsNonFolderRootItems() {
        ResourceItemMetadata fileInRoot = new ResourceItemMetadata();
        fileInRoot.setNodeType(NodeType.ITEM);
        fileInRoot.setName("stray-file");
        fileInRoot.setCreatedAt(System.currentTimeMillis() - DEFAULT_TTL - 1000);

        ResourceFolderMetadata rootFolder = new ResourceFolderMetadata();
        rootFolder.setItems(List.of(fileInRoot));

        when(resourceService.getFolderMetadata(any(), any(), anyInt(), anyBoolean()))
                .thenReturn(rootFolder);

        triggerCleanup();

        verify(resourceService, never()).deleteResource(any(), any());
    }

    @Test
    void testCleanup_deleteFailureIsSwallowed() {
        long createdAt = System.currentTimeMillis() - DEFAULT_TTL - 1000;

        ResourceItemMetadata folderItem = new ResourceItemMetadata();
        folderItem.setNodeType(NodeType.FOLDER);
        folderItem.setName("deploy1");

        ResourceFolderMetadata rootFolder = new ResourceFolderMetadata();
        rootFolder.setItems(List.of(folderItem));

        ResourceItemMetadata expiredItem = new ResourceItemMetadata();
        expiredItem.setNodeType(NodeType.ITEM);
        expiredItem.setName("uuid-err");
        expiredItem.setCreatedAt(createdAt);

        ResourceFolderMetadata deployFolder = new ResourceFolderMetadata();
        deployFolder.setItems(List.of(expiredItem));

        when(resourceService.getFolderMetadata(any(), any(), anyInt(), anyBoolean()))
                .thenReturn(rootFolder)
                .thenReturn(deployFolder);
        when(resourceService.deleteResource(any(), any())).thenThrow(new RuntimeException("storage error"));

        triggerCleanup(); // must not throw
    }

    @Test
    void testCleanup_paginatesDeploymentFolder() {
        long createdAt = System.currentTimeMillis() - DEFAULT_TTL - 1000;

        ResourceItemMetadata folderItem = new ResourceItemMetadata();
        folderItem.setNodeType(NodeType.FOLDER);
        folderItem.setName("deploy1");

        ResourceFolderMetadata rootFolder = new ResourceFolderMetadata();
        rootFolder.setItems(List.of(folderItem));

        ResourceItemMetadata freshItem = new ResourceItemMetadata();
        freshItem.setNodeType(NodeType.ITEM);
        freshItem.setName("uuid-fresh");
        freshItem.setCreatedAt(System.currentTimeMillis());

        ResourceFolderMetadata deployFolderPage1 = new ResourceFolderMetadata();
        deployFolderPage1.setItems(List.of(freshItem));
        deployFolderPage1.setNextToken("page2-token");

        ResourceItemMetadata expiredItem = new ResourceItemMetadata();
        expiredItem.setNodeType(NodeType.ITEM);
        expiredItem.setName("uuid-old");
        expiredItem.setCreatedAt(createdAt);

        ResourceFolderMetadata deployFolderPage2 = new ResourceFolderMetadata();
        deployFolderPage2.setItems(List.of(expiredItem));

        when(resourceService.getFolderMetadata(any(), any(), anyInt(), anyBoolean()))
                .thenReturn(rootFolder)
                .thenReturn(deployFolderPage1)
                .thenReturn(deployFolderPage2);

        triggerCleanup();

        ResourceDescriptor expected = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.RESPONSE_MAPPING, "response_mappings", "response_mappings/", "deploy1/uuid-old");
        verify(resourceService).deleteResource(eq(expected), eq(EtagHeader.ANY));
    }
}
