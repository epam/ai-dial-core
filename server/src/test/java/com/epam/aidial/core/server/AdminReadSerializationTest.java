package com.epam.aidial.core.server;

import com.epam.aidial.core.storage.blobstore.BlobStorageUtil;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.LockService;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Sibling to {@link AdminWriteSerializationTest}: the full-rebuild blob scan (reload / rebuildNow /
 * debounced timer) acquires the same {@code underBucketLocks(MergedConfigStore.ADMIN_BUCKET_LOCATIONS, ...)}
 * the writers use, so a concurrent admin write on another pod cannot leak partial-batch blob state
 * into a replica's {@code MergedConfigStore} (PR #1529 thread r3302786719, design 02 §4.4). Holding
 * the platform-bucket lock from the test must block {@code POST /v1/ops/config/reload} until release.
 */
public class AdminReadSerializationTest extends ResourceBaseTest {

    @Test
    @SneakyThrows
    void testReloadBlocksWhileAdminLockHeld() {
        LockService lockService = dial.getProxy().getLockService();
        String contendingKey = BlobStorageUtil.toStoragePath(
                lockService.getPrefix(), ResourceDescriptor.PLATFORM_LOCATION);
        LockService.Lock held = lockService.lock(contendingKey);

        CompletableFuture<Response> future = CompletableFuture.supplyAsync(() ->
                send(HttpMethod.POST, "/v1/ops/config/reload", null, "", "authorization", "admin"));
        try {
            assertThrows(TimeoutException.class, () -> future.get(500, TimeUnit.MILLISECONDS),
                    "config reload must block while the platform-bucket admin lock is held — "
                            + "proves MergedConfigStore.reload() acquires the same bucket locks as writers");
        } finally {
            held.close();
        }
        Response response = future.get(10, TimeUnit.SECONDS);
        assertEquals(200, response.status(),
                "reload must succeed once the lock is released. body=" + response.body());
    }
}
