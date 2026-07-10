package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ResponseMapping;
import com.epam.aidial.core.server.util.ResponseIdUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.blobstore.BlobStorageUtil;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.data.NodeType;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.annotations.VisibleForTesting;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RFuture;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.ScoredEntry;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;

@Slf4j
public class BackgroundJobScheduler {

    private static final int PAGE_SIZE = 1000;

    private final String prefix;
    private final Settings settings;
    private final Vertx vertx;
    private final RedissonClient redis;
    private final ResourceService resourceService;
    private final AsyncTaskExecutor taskExecutor;
    private final BackgroundJobService service;
    private final RScript script;
    private final RScoredSortedSet<String> schedule;
    private final String scheduleKey;
    private final Set<String> inFlightJobIds = ConcurrentHashMap.newKeySet();
    private final AtomicLong nextJobTime = new AtomicLong(Long.MAX_VALUE);

    public BackgroundJobScheduler(
            Vertx vertx,
            RedissonClient redis,
            String prefix,
            ResourceService resourceService,
            AsyncTaskExecutor taskExecutor,
            Settings settings,
            BackgroundJobService service) {
        this.prefix = prefix;
        this.settings = settings;
        this.vertx = vertx;
        this.redis = redis;
        this.resourceService = resourceService;
        this.taskExecutor = taskExecutor;
        this.service = service;
        this.script = redis.getScript(StringCodec.INSTANCE);
        this.scheduleKey = "background_job_schedule:" + BlobStorageUtil.toStoragePath(prefix, "queue");
        this.schedule = redis.getScoredSortedSet(scheduleKey, StringCodec.INSTANCE);
    }

    public void init() {
        AtomicBoolean polling = new AtomicBoolean(false);
        vertx.setPeriodic(0, settings.getSchedulerTickIntervalMs(), id -> {
            if (nextJobTime.get() <= System.currentTimeMillis()
                    && polling.compareAndSet(false, true)) {
                taskExecutor.submit(this::tryClaimAndPoll)
                        .onComplete(ignore -> polling.set(false))
                        .onFailure(e -> log.warn("Failed to poll background jobs", e));
            }
        });
        // Set up periodic scans in case of a Redis restart
        vertx.setPeriodic(0, settings.getScanIntervalMs(), id -> taskExecutor.submit(this::scan));
    }

    public Future<Void> saveJob(ProxyContext context) {
        String dialId = context.getDialResponseId();
        return service.persistJobRecord(context)
                .onSuccess(ignore -> schedule(dialId, System.currentTimeMillis() + settings.getInitialPollIntervalMs()));
    }

    private void schedule(String dialId, long nextPollTime) {
        toFuture(schedule.addIfAbsentAsync(nextPollTime, dialId))
                .onSuccess(added -> {
                    if (added) {
                        nextJobTime.accumulateAndGet(nextPollTime, Math::min);
                    }
                })
                .onFailure(e -> log.warn("Failed to schedule background job {}", dialId, e));
    }

    public Future<Boolean> isJobActive(String dialId) {
        return service.isJobActive(dialId);
    }

    public Future<Boolean> finishStreamingJob(String dialId) {
        return service.deleteJobRecord(dialId)
                .compose(deleted -> {
                    if (!deleted) {
                        log.info("Streaming job {} record already deleted, skipping completion processing", dialId);
                        return Future.succeededFuture(false);
                    }
                    return removeFromSchedule(dialId)
                            .recover(e -> {
                                log.warn("Failed to remove streaming job {} from Redis schedule", dialId, e);
                                return Future.succeededFuture();
                            })
                            .map(true);
                });
    }

    public Future<Void> tryCompleteOnGet(String dialId, ResponseMapping mapping, ResponsesApiClient.TerminalResult result) {
        return service.tryCompleteOnGet(dialId, mapping, result)
                .compose(completed -> completed ? removeFromSchedule(dialId) : Future.succeededFuture());
    }

    private Void tryClaimAndPoll() {
        if (inFlightJobIds.size() >= settings.getMaxParallelJobs()) {
            return null;
        }

        while (true) {
            long previous = nextJobTime.get();
            ScoredEntry<String> scoredEntry = schedule.firstEntry();
            if (scoredEntry == null) {
                nextJobTime.compareAndSet(previous, Long.MAX_VALUE);
                break;
            }

            long now = System.currentTimeMillis();
            long score = scoredEntry.getScore() == null ? now : scoredEntry.getScore().longValue();
            if (score > now) {
                nextJobTime.compareAndSet(previous, score);
                break;
            }

            if (inFlightJobIds.size() >= settings.getMaxParallelJobs()) {
                break;
            }

            String dialId = scoredEntry.getValue();
            if (!inFlightJobIds.add(dialId)) {
                // Already polling
                continue;
            }

            try {
                ClaimResult claimResult = executeClaim(scoredEntry);
                if (claimResult == null) {
                    inFlightJobIds.remove(dialId);
                    continue;
                }
                BackgroundJobService.Poller poller = service.jobPoller(dialId);
                if (poller == null) {
                    log.info("Background job {} record or mapping not found, stopping polling", dialId);
                    executeComplete(dialId, claimResult.owner());
                    inFlightJobIds.remove(dialId);
                    continue;
                }

                poller.poll()
                        .compose(finished -> {
                            if (finished) {
                                return executeComplete(dialId, claimResult.owner());
                            }
                            long backoff = (long) Math.min(
                                    settings.getInitialPollIntervalMs() * Math.pow(settings.getPollBackoffFactor(), claimResult.attempts()),
                                    settings.getMaxPollIntervalMs());
                            long nextPollTime = System.currentTimeMillis() + backoff;
                            return executeReschedule(dialId, claimResult.owner(), claimResult.attempts() + 1, 0, nextPollTime);
                        },
                        error -> {
                            int newErrors = claimResult.errors() + 1;
                            if (newErrors >= settings.getMaxSequentialPollFailures()) {
                                log.error("Background job {} exceeded max poll failures, giving up", dialId, error);
                                return executeComplete(dialId, claimResult.owner())
                                        .eventually(poller::fail);
                            }
                            log.warn("Poll failed for background job {} ({}/{})", dialId, newErrors, settings.getMaxSequentialPollFailures(), error);
                            long nextPollTime = System.currentTimeMillis() + settings.getInitialPollIntervalMs();
                            return executeReschedule(dialId, claimResult.owner(), claimResult.attempts(), newErrors, nextPollTime);
                        })
                        .eventually(() -> Future.succeededFuture(inFlightJobIds.remove(dialId)));
            } catch (Throwable e) {
                log.warn("Failed to process background job {}", dialId, e);
                inFlightJobIds.remove(dialId);
            }
        }

        return null;
    }

    @Nullable
    private ClaimResult executeClaim(ScoredEntry<String> entry) {
        long leaseUntil = System.currentTimeMillis() + settings.getLeaseTimeoutMs();
        long owner = ThreadLocalRandom.current().nextLong();
        List<Object> result = script.eval(
                RScript.Mode.READ_WRITE,
                """
                        local score = redis.call('ZSCORE', ARGV[1], ARGV[4])
                        if score == false or tonumber(score) > tonumber(ARGV[2]) then
                            return {}
                        end
                        redis.call('HSET', KEYS[1], 'owner', ARGV[5])
                        redis.call('ZADD', ARGV[1], tonumber(ARGV[3]), ARGV[4])
                        local attempts = redis.call('HGET', KEYS[1], 'attempts')
                        local errors = redis.call('HGET', KEYS[1], 'errors')
                        return {attempts or '0', errors or '0'}
                        """,
                RScript.ReturnType.MULTI,
                List.of(stateKey(entry.getValue())),
                scheduleKey,
                String.valueOf(entry.getScore()),
                String.valueOf(leaseUntil),
                entry.getValue(),
                String.valueOf(owner));

        if (result.isEmpty()) {
            return null;
        }
        int attempts = Integer.parseInt((String) result.get(0));
        int errors = Integer.parseInt((String) result.get(1));

        return new ClaimResult(owner, attempts, errors);
    }

    private Future<Void> executeReschedule(String dialId, long owner, int attempts, int errors, long nextPollTime) {
        return toFuture(script.<Long>evalAsync(
                RScript.Mode.READ_WRITE,
                """
                        local owner = redis.call('HGET', KEYS[1], 'owner')
                        if owner ~= ARGV[3] then
                            return 0
                        end
                        redis.call('ZADD', ARGV[1], tonumber(ARGV[4]), ARGV[2])
                        redis.call('HSET', KEYS[1], 'attempts', ARGV[5])
                        redis.call('HSET', KEYS[1], 'errors', ARGV[6])
                        return 1
                        """,
                RScript.ReturnType.INTEGER,
                List.of(stateKey(dialId)),
                scheduleKey,
                dialId,
                String.valueOf(owner),
                String.valueOf(nextPollTime),
                String.valueOf(attempts),
                String.valueOf(errors)))
                .map(result -> {
                    if (result == 1L) {
                        log.debug("Background job {} rescheduled", dialId);
                    } else {
                        log.warn("Background job {} reschedule skipped (owner mismatch)", dialId);
                    }

                    return null;
                });
    }

    private Future<Void> executeComplete(String dialId, long owner) {
        return toFuture(script.<Long>evalAsync(
                RScript.Mode.READ_WRITE,
                """
                        local owner = redis.call('HGET', KEYS[1], 'owner')
                        if owner ~= false and owner ~= ARGV[3] then
                            return 0
                        end
                        redis.call('ZREM', ARGV[1], ARGV[2])
                        redis.call('DEL', KEYS[1])
                        return 1
                        """,
                RScript.ReturnType.INTEGER,
                List.of(stateKey(dialId)),
                scheduleKey,
                dialId,
                String.valueOf(owner)))
                .map(result -> {
                    if (result == 1L) {
                        log.info("Background job {} completed and removed from Redis", dialId);
                    } else {
                        log.warn("Background job {} complete skipped (owner mismatch)", dialId);
                    }
                    return null;
                });
    }

    private Future<Void> removeFromSchedule(String dialId) {
        return toFuture(schedule.removeAsync(dialId))
                .compose(ignored -> toFuture(redis.getMap(stateKey(dialId), StringCodec.INSTANCE).deleteAsync()))
                .mapEmpty();
    }

    private <T> Future<T> toFuture(RFuture<T> rf) {
        return Future.fromCompletionStage(rf.toCompletableFuture(), vertx.getOrCreateContext());
    }

    private String stateKey(String dialId) {
        return "background_job_state:" + BlobStorageUtil.toStoragePath(prefix, dialId);
    }

    @VisibleForTesting
    Void scan() {
        log.info("Scanning for unscheduled jobs");
        try {
            ResourceDescriptor root = ResponseIdUtil.getBackgroundJobDescriptor(null);
            String token = null;
            do {
                ResourceFolderMetadata folder = resourceService.getFolderMetadata(root, token, PAGE_SIZE, false);
                if (folder == null) {
                    break;
                }
                List<? extends MetadataBase> items = folder.getItems();
                if (items != null) {
                    long now = System.currentTimeMillis();
                    for (MetadataBase item : items) {
                        if (item.getNodeType() == NodeType.ITEM) {
                            if (!(item instanceof ResourceItemMetadata meta)) {
                                log.warn("Unexpected item type {}", item.getClass());
                                continue;
                            }

                            if (meta.getCreatedAt() == null) {
                                log.debug("Missing createdAt for job {}, fetching metadata", meta.getDescriptor());
                                meta = resourceService.getResourceMetadata(meta.getDescriptor());
                            }

                            if (meta == null) {
                                log.info("Job {} metadata not found, skipping", item.getDescriptor());
                                continue;
                            }

                            if (meta.getCreatedAt() == null) {
                                log.warn("Missing createdAt for job {}, skipping", meta.getDescriptor());
                                continue;
                            }

                            String dialId = item.getName();
                            if (now - meta.getCreatedAt() >= settings.getJobTtlMs()) {
                                log.warn("Expiring job {}, age={}ms", dialId, now - meta.getCreatedAt());
                                service.expireJob(dialId);
                            } else {
                                schedule(dialId, now);
                            }
                        }
                    }
                }
                token = folder.getNextToken();
            } while (token != null);
        } catch (Throwable e) {
            log.warn("Scan failed", e);
        }

        return null;
    }

    public long getJobTtlMs() {
        return settings.getJobTtlMs();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class Settings {
        long initialPollIntervalMs = TimeUnit.SECONDS.toMillis(10);
        long maxPollIntervalMs = TimeUnit.MINUTES.toMillis(5);
        double pollBackoffFactor = 2.0;
        int maxSequentialPollFailures = 10;
        long jobTtlMs = TimeUnit.DAYS.toMillis(1);
        long leaseTimeoutMs = TimeUnit.MINUTES.toMillis(5);
        int maxParallelJobs = 100;
        long scanIntervalMs = TimeUnit.MINUTES.toMillis(10);
        long schedulerTickIntervalMs = TimeUnit.SECONDS.toMillis(5);
    }

    private record ClaimResult(long owner, int attempts, int errors) {
    }
}
