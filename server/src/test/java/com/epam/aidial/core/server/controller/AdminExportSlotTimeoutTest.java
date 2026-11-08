package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.service.ExportLimits;
import com.epam.aidial.core.server.service.ExportPlanner;
import com.epam.aidial.core.server.service.ExportTestNodes;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.service.LockService;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A plan that outlasts the deadline, on real threads and a real lock in Redis: the deadline interrupts the plan thread, the plan
 * ignores the interrupt and fails with 504 while the flag is still set, and the slot must be free at once, not at the end of its
 * lease.
 */
@ExtendWith(MockitoExtension.class)
public class AdminExportSlotTimeoutTest {

    private static final String BODY = "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/a\"}]}";
    private static final int PORT = 16373;

    private static RedisServer server;
    private static RedissonClient client;
    private static LockService lockService;

    @Mock
    private ProxyContext context;
    @Mock
    private ConfigAuthorizationService authorizationService;
    @Mock
    private AccessService accessService;
    @Mock
    private AsyncTaskExecutor taskExecutor;
    @Mock
    private Vertx vertx;
    @Mock
    private ExportPlanner planner;

    @BeforeAll
    static void startRedis() throws IOException {
        try {
            server = RedisServer.newRedisServer().port(PORT).bind("127.0.0.1").setting("maxmemory 4M").build();
            server.start();
            Config config = new Config();
            config.useSingleServer().setAddress("redis://localhost:" + PORT);
            client = Redisson.create(config);
            lockService = new LockService(client, null);
        } catch (Throwable e) {
            stopRedis();
            throw e;
        }
    }

    @AfterAll
    static void stopRedis() throws IOException {
        try {
            if (client != null) {
                client.shutdown();
            }
        } finally {
            if (server != null) {
                server.stop();
            }
        }
    }

    private AdminExportController controller() {
        ExportLimits limits = new ExportLimits(100, 500, 10, 10_000, 4 * 1024 * 1024, 32 * 1024 * 1024, Duration.ofMillis(200));
        return new AdminExportController(context, authorizationService, taskExecutor, vertx, Duration.ofSeconds(1),
                accessService, () -> planner, null, ExportTestNodes.NO_CONTENT, "test", lockService, limits);
    }

    /** Runs each task on a thread of its own, as the executor does, and completes a future of no context. */
    private void runTasksOnVirtualThreads() {
        lenient().when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> task = invocation.getArgument(0);
            Promise<Object> promise = Promise.promise();
            Thread.ofVirtual().start(() -> {
                try {
                    promise.complete(task.call());
                } catch (Throwable e) {
                    promise.fail(e);
                }
            });
            return promise.future();
        });
    }

    /** Fires the handler of a timer on a thread of its own after the delay, as the event loop does. */
    @SuppressWarnings("unchecked")
    private void fireTimersOnTime() {
        lenient().when(vertx.setTimer(anyLong(), any(Handler.class))).thenAnswer(invocation -> {
            long delay = invocation.getArgument(0);
            Handler<Long> handler = invocation.getArgument(1);
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    return;
                }
                handler.handle(1L);
            });
            return 1L;
        });
    }

    @Test
    public void testPlanThatIgnoresTheInterruptEndsWith504AndLeavesTheSlotFree() throws Exception {
        runTasksOnVirtualThreads();
        fireTimersOnTime();
        AtomicBoolean interruptedWhenItFailed = new AtomicBoolean();
        when(planner.plan(any(), any(), any(), any())).thenAnswer(invocation -> {
            // a busy loop, which an interrupt cannot end: it only sets the flag
            while (!Thread.currentThread().isInterrupted()) {
                Thread.onSpinWait();
            }
            interruptedWhenItFailed.set(Thread.currentThread().isInterrupted());
            throw new HttpException(HttpStatus.GATEWAY_TIMEOUT, "The export took too long");
        });
        CountDownLatch answered = new CountDownLatch(1);
        when(context.respond(any(Throwable.class), anyString())).thenAnswer(invocation -> {
            answered.countDown();
            return Future.succeededFuture();
        });

        controller().exportSelection(Buffer.buffer(BODY));

        assertTrue(answered.await(10, TimeUnit.SECONDS), "the export answers");
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(context).respond(error.capture(), anyString());
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, ((HttpException) error.getValue()).getStatus(), "the timeout stays a timeout");
        assertTrue(interruptedWhenItFailed.get(), "the plan failed with the interrupt flag still set, the case under test");

        LockService.Lock next = lockService.tryLock(AdminExportController.SELECTION_SLOT);
        assertNotNull(next, "the slot is free at once and not at the end of its lease of 300 s");
        next.close();
    }
}
