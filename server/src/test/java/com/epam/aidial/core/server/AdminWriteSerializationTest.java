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
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice 4S.3 — admin writes (per-entity + bulk apply) serialize cluster-wide via the global
 * admin-write lock. The test acquires the lock from the test thread and confirms an in-flight admin
 * write request cannot make progress until the lock is released — proving the controllers actually
 * take the same lock the test holds.
 *
 * <p>U.0 (2026-05-20): POST is removed from the lock scope — POST at the single-entity surface
 * returns 405 before any write logic runs; only PUT (upsert) and DELETE acquire the admin-write
 * lock. The PUT path now covers both create and update arms.
 */
public class AdminWriteSerializationTest extends ResourceBaseTest {

    @Test
    @SneakyThrows
    void testAdminApplyBlocksWhileGlobalLockHeld() {
        String applyBody = """
                {
                  "manifests": [
                    {
                      "kind": "Interceptor",
                      "name": "interceptors/platform/lock-test-int",
                      "spec": {"endpoint": "http://localhost:4088/api/v1/interceptor/handle"}
                    }
                  ]
                }
                """;
        runBlockedByAdminWriteLock(() ->
                send(HttpMethod.POST, "/v1/admin/apply", null, applyBody, "authorization", "admin"));
        verify(send(HttpMethod.GET, "/v1/interceptors/platform/lock-test-int", null, "",
                "authorization", "admin"), 200);
    }

    @Test
    @SneakyThrows
    void testPerEntityPutCreateBlocksWhileGlobalLockHeld() {
        String interceptorBody = """
                {"endpoint": "http://localhost:4088/api/v1/interceptor/handle"}
                """;
        runBlockedByAdminWriteLock(() ->
                send(HttpMethod.PUT, "/v1/interceptors/platform/lock-test-create", null,
                        interceptorBody, "authorization", "admin", "If-None-Match", "*"));
        verify(send(HttpMethod.GET, "/v1/interceptors/platform/lock-test-create", null, "",
                "authorization", "admin"), 200);
    }

    @Test
    @SneakyThrows
    void testPerEntityPutAndDeleteBlockWhileGlobalLockHeld() {
        verify(send(HttpMethod.PUT, "/v1/interceptors/platform/lock-test-put", null,
                "{\"endpoint\": \"http://localhost:4088/api/v1/interceptor/handle\"}",
                "authorization", "admin", "If-None-Match", "*"), 200);

        String updateBody = """
                {"endpoint": "http://localhost:4088/api/v1/interceptor/handle/v2"}
                """;
        runBlockedByAdminWriteLock(() ->
                send(HttpMethod.PUT, "/v1/interceptors/platform/lock-test-put", null,
                        updateBody, "authorization", "admin"));

        runBlockedByAdminWriteLock(() ->
                send(HttpMethod.DELETE, "/v1/interceptors/platform/lock-test-put", null, "",
                        "authorization", "admin"));

        verify(send(HttpMethod.GET, "/v1/interceptors/platform/lock-test-put", null, "",
                "authorization", "admin"), 404);
    }

    @SneakyThrows
    private void runBlockedByAdminWriteLock(Supplier<Response> writeCall) {
        LockService lockService = dial.getProxy().getLockService();
        // Admin writes acquire both PUBLIC and PLATFORM bucket locks via LockService.underBucketLocks;
        // holding either one is sufficient to block the controller. PLATFORM_LOCATION matches the
        // test's write targets (`/v1/interceptors/platform/...`, `/v1/admin/apply` for platform-bucket entities).
        String contendingKey = BlobStorageUtil.toStoragePath(
                lockService.getPrefix(), ResourceDescriptor.PLATFORM_LOCATION);
        LockService.Lock held = lockService.lock(contendingKey);
        CompletableFuture<Response> future = CompletableFuture.supplyAsync(writeCall::get);
        try {
            assertThrows(TimeoutException.class, () -> future.get(500, TimeUnit.MILLISECONDS),
                    "admin write must block while the global admin-write lock is held");
        } finally {
            held.close();
        }
        Response response = future.get(10, TimeUnit.SECONDS);
        int s = response.status();
        assertTrue(s == 200 || s == 201 || s == 204,
                () -> "admin write must succeed after lock release. status=" + s + " body=" + response.body());
    }
}
