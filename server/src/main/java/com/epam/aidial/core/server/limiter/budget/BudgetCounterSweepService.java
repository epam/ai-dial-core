package com.epam.aidial.core.server.limiter.budget;

import com.epam.aidial.core.config.policy.PolicyAssignment;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.limiter.CalendarPeriod;
import com.epam.aidial.core.server.limiter.CalendarWindowCalculator;
import com.epam.aidial.core.server.limiter.CostFixedRateBucket;
import com.epam.aidial.core.server.service.policy.PolicyRecordCodec;
import com.epam.aidial.core.server.service.policy.PolicyService;
import com.epam.aidial.core.server.tracing.BackgroundOperationTracer;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.service.TimerService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Periodic sweep that deletes the spend counters of budgets that no longer exist. A counter row is removed when
 * both its windows have closed and no assignment (or self-applying policy) is stored for its budget instance.
 * A row of a live budget is never removed, and a row with an open window stays readable until it closes.
 *
 * <p>A tick claims the next page of rows and advances the shared cursor in Redis under a brief cluster lock, then
 * releases the lock and handles the page, so another pod can claim the following page meanwhile. The cursor moves
 * before the rows are handled; a page lost to a crash is reached again by the next pass.
 */
@Slf4j
public class BudgetCounterSweepService implements AutoCloseable {

    private static final String BUDGETS_FOLDER = "budgets";
    private static final String COSTS_ROW = "costs";
    // folders above a row: <costObject>/budgets/<instance>/<user>/<model>
    private static final int ROW_FOLDERS = 5;
    private static final String STATE_KEY = "budget_counter_sweep";

    private final ResourceService resourceService;
    private final LockService lockService;
    private final RedissonClient redis;
    private final ConfigStore configStore;
    private final Settings settings;
    private final TimerService.Timer timer;
    private final String stateKey;
    private final ResourceDescriptor limitsFolder = ResourceDescriptorFactory.fromDecoded(
            ResourceTypes.LIMIT, ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION, "");

    private final Counter scannedCounter = Counter.builder("dial_budget_sweep_scanned_total").register(Metrics.globalRegistry);
    private final Counter deletedCounter = Counter.builder("dial_budget_sweep_deleted_total").register(Metrics.globalRegistry);

    public BudgetCounterSweepService(TimerService timerService, ResourceService resourceService, LockService lockService,
                                     RedissonClient redis, ConfigStore configStore, Settings settings,
                                     BackgroundOperationTracer backgroundTracer) {
        this.resourceService = resourceService;
        this.lockService = lockService;
        this.redis = redis;
        this.configStore = configStore;
        this.settings = settings;
        this.stateKey = STATE_KEY + ":" + lockService.getPrefix();
        this.timer = timerService.scheduleWithFixedDelay(settings.getPeriod(), settings.getPeriod(),
                () -> backgroundTracer.trace(this::tick));
    }

    @SneakyThrows
    @Override
    public void close() {
        timer.close();
    }

    private void tick() {
        tick(System.currentTimeMillis());
    }

    @VisibleForTesting
    void tick(long now) {
        try {
            ResourceFolderMetadata page = claimPage();
            if (page == null) {
                return;
            }
            for (MetadataBase meta : page.getItems()) {
                try {
                    if (meta instanceof ResourceItemMetadata row) {
                        sweepRow(row.getDescriptor(), now);
                    }
                } catch (Throwable e) {
                    log.warn("Failed to sweep budget counter: {}", meta.getUrl(), e);
                }
            }
        } catch (Throwable e) {
            log.warn("Failed to run budget counter sweep tick", e);
        }
    }

    /**
     * Lists the page after the cursor and moves the cursor past it under a brief lock. Returns {@code null} when
     * another tick is claiming a page or there is nothing to list. Once a pass completes the cursor is cleared, so the
     * next tick starts a new pass.
     */
    @VisibleForTesting
    @Nullable
    ResourceFolderMetadata claimPage() {
        try (LockService.Lock lock = lockService.tryLock(stateKey)) {
            if (lock == null) {
                return null;
            }
            RBucket<String> cursor = redis.getBucket(stateKey, StringCodec.INSTANCE);
            ResourceFolderMetadata page = resourceService.getFolderMetadata(limitsFolder, cursor.get(), settings.getBatch(), true);
            if (page == null || page.getNextToken() == null) {
                cursor.delete();
            } else {
                cursor.set(page.getNextToken());
            }
            return page;
        }
    }

    private void sweepRow(ResourceDescriptor row, long now) {
        List<String> folders = row.getParentFolders();
        if (folders.size() != ROW_FOLDERS || !BUDGETS_FOLDER.equals(folders.get(1)) || !COSTS_ROW.equals(row.getName())) {
            return;
        }
        scannedCounter.increment();
        if (isLive(folders.get(0), folders.get(2), now)) {
            return;
        }
        try (LockService.Lock lock = resourceService.tryLockResource(row)) {
            if (lock == null) {
                return;
            }
            String json = resourceService.getResource(row, EtagHeader.ANY, false);
            if (json == null || !isClosed(ProxyUtil.convertToObject(json, BudgetCounter.class), now)) {
                return;
            }
            if (resourceService.deleteResource(row, EtagHeader.ANY, false)) {
                deletedCounter.increment();
            }
        }
    }

    /**
     * Read from storage rather than from the config, which can lag behind a write made on another pod.
     */
    private boolean isLive(String costObject, String instanceKey, long now) {
        String path = costObject + "/" + instanceKey;
        ResourceDescriptor assignment = PolicyService.describe(ResourceTypes.POLICY_ASSIGNMENT, path);
        String assignmentJson = resourceService.getResource(assignment);
        if (assignmentJson != null) {
            PolicyAssignment record = PolicyRecordCodec.readAssignment(assignmentJson);
            return record.getExpiresAt() == null || OffsetDateTime.parse(record.getExpiresAt()).toInstant().isAfter(Instant.ofEpochMilli(now));
        }
        return resourceService.hasResource(PolicyService.describe(ResourceTypes.POLICY, path));
    }

    private boolean isClosed(BudgetCounter counter, long now) {
        return isClosed(counter.getDay(), CalendarPeriod.DAY, now) && isClosed(counter.getMonth(), CalendarPeriod.MONTH, now);
    }

    private boolean isClosed(CostFixedRateBucket bucket, CalendarPeriod period, long now) {
        return bucket.getPeriodStart() == Long.MIN_VALUE
                || CalendarWindowCalculator.nextPeriodStart(period, bucket.getPeriodStart(), configStore.get().getRateLimitSchedule()) <= now;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Settings {
        /**
         * Period in milliseconds between sweep ticks.
         */
        private long period = 300_000;
        /**
         * Number of counter rows handled per tick.
         */
        private int batch = 500;
    }
}
