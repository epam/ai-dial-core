package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.storage.service.ResourceService;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class MergedConfigStoreTest {

    @Mock
    private Vertx vertx;
    @Mock
    private ResourceService resourceService;
    @Mock
    private ApiKeyStore apiKeyStore;
    @Mock
    private SecretFieldProcessor secretFieldProcessor;
    @Mock
    private FileConfigStore fileConfigStore;

    @Test
    public void testRequestRebuildIsNoOpBeforeInit() {
        MergedConfigStore store = new MergedConfigStore(
                vertx, resourceService, apiKeyStore, new PlatformEntityLocationStrategy(),
                secretFieldProcessor, MergedConfigStore.MODE_ABORT);

        store.requestRebuild();
        store.requestRebuild();

        verify(vertx, never()).setTimer(anyLong(), any());
        verify(vertx, never()).cancelTimer(anyLong());
    }

    @Test
    public void testRebuildNowCancelsPendingTimer() {
        long sentinelTimerId = 42L;
        when(vertx.setTimer(anyLong(), any())).thenReturn(sentinelTimerId);
        when(fileConfigStore.get()).thenReturn(new Config());

        MergedConfigStore store = new MergedConfigStore(
                vertx, resourceService, apiKeyStore, new PlatformEntityLocationStrategy(),
                secretFieldProcessor, MergedConfigStore.MODE_ABORT);
        store.init(fileConfigStore);

        store.requestRebuild();
        Config rebuilt = store.rebuildNow();

        verify(vertx, times(1)).cancelTimer(eq(sentinelTimerId));
        org.junit.jupiter.api.Assertions.assertNotNull(rebuilt);
    }
}
