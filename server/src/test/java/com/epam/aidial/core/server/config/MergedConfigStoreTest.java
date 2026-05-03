package com.epam.aidial.core.server.config;

import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.storage.service.ResourceService;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class MergedConfigStoreTest {

    @Mock
    private Vertx vertx;
    @Mock
    private ResourceService resourceService;
    @Mock
    private ApiKeyStore apiKeyStore;

    @Test
    public void testRequestRebuildIsNoOpBeforeInit() {
        MergedConfigStore store = new MergedConfigStore(
                vertx, resourceService, apiKeyStore, new PlatformEntityLocationStrategy(),
                MergedConfigStore.MODE_ABORT);

        store.requestRebuild();
        store.requestRebuild();

        verify(vertx, never()).setTimer(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());
        verify(vertx, never()).cancelTimer(org.mockito.ArgumentMatchers.anyLong());
    }
}
