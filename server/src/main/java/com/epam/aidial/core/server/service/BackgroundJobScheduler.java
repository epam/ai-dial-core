package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.util.ResponseIdUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.data.NodeType;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.google.common.annotations.VisibleForTesting;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;

@Slf4j
class BackgroundJobScheduler {

    private static final int PAGE_SIZE = 1000;

    private final String keyTag;
    private final BackgroundJobService.Settings settings;
    private final Vertx vertx;
    private final RedissonClient redis;
    private final ResourceService resourceService;
    private final AsyncTaskExecutor taskExecutor;
    private final Function<String, BackgroundJobService.Poller> jobPollerProvider;
    private final Consumer<String> jobExpirer;
    private final RScript script;
    private final RScoredSortedSet<String> schedule;
    private final String scheduleKey;
    private final Set<String> inFlightJobIds = ConcurrentHashMap.newKeySet();
    private final AtomicLong nextJobTime = new AtomicLong(Long.MAX_VALUE);

    BackgroundJobScheduler(
            Vertx vertx,
            RedissonClient redis,
            String prefix,
            ResourceService resourceService,
            AsyncTaskExecutor taskExecutor,
            BackgroundJobService.Settings settings,
            Function<String, BackgroundJobService.Poller> jobPollerProvider,
            Consumer<String> jobExpirer) {
        // The hash tag is used to force the keys to be stored in the same hash slot.
        // https://redis.io/docs/latest/operate/oss_and_stack/reference/cluster-spec/#implemented-subset
        // The same should work for Valkey: https://valkey.io/topics/cluster-spec/
        //
        // For example:
        // ~/redis-cluster-test$ redis-cli -p 7000 CLUSTER KEYSLOT 'background_job_schedule:{background_jobs:test-prefix}:queue'
        // (integer) 14335
        // ~/redis-cluster-test$ redis-cli -p 7000 CLUSTER KEYSLOT 'background_job_state:{background_jobs:test-prefix}:dial_gpt-5.4-2026-03-05_858f1fa2488f4bb6b893925866c9da76'
        // (integer) 14335
        // ~/redis-cluster-test$ redis-cli -p 7000 CLUSTER KEYSLOT 'background_job_state:{background_jobs:test-prefix}:dial_gpt-5.6-terra-2026-07-09_29a1981bd72a4bd19a8a35007fdaf1b7'
        // (integer) 14335
        //
        // With a different prefix, the key is assigned to a different hash slot:
        // ~/redis-cluster-test$ redis-cli -p 7000 CLUSTER KEYSLOT 'background_job_state:{background_jobs:another-prefix}:dial_gpt-5.6-terra-2026-07-09_29a1981bd72a4bd19a8a35007fdaf1b7'
        // (integer) 8387
        this.keyTag = "{background_jobs:" + prefix + "}";
        this.settings = settings;
        this.vertx = vertx;
        this.redis = redis;
        this.resourceService = resourceService;
        this.taskExecutor = taskExecutor;
        this.jobPollerProvider = jobPollerProvider;
        this.jobExpirer = jobExpirer;
        this.script = redis.getScript(StringCodec.INSTANCE);
        this.scheduleKey = "background_job_schedule:" + keyTag + ":queue";
        this.schedule = redis.getScoredSortedSet(scheduleKey, StringCodec.INSTANCE);
    }

    void init() {
        AtomicBoolean polling = new AtomicBoolean(false);
        vertx.setPeriodic(0, settings.getSchedulerTickIntervalMs(), id -> {
            if (nextJobTime.get() <= System.currentTimeMillis()
                    && polling.compareAndSet(false, true)) {
                taskExecutor.submit(this::pollJobs)
                        .onComplete(ignore -> polling.set(false))
                        .onFailure(e -> log.warn("Failed to poll background jobs", e));
            }
        });
        // Set up periodic scans in case of a Redis restart
        vertx.setPeriodic(0, settings.getScanIntervalMs(), id -> taskExecutor.submit(this::scan));
    }

    void schedule(String jobId, long nextPollTime) {
        toFuture(schedule.addIfAbsentAsync(nextPollTime, jobId))
                .onSuccess(added -> {
                    if (added) {
                        nextJobTime.accumulateAndGet(nextPollTime, Math::min);
                    }
                })
                .onFailure(e -> log.warn("Failed to schedule background job {}", jobId, e));
    }

    Future<Void> cancel(String jobId) {
        return toFuture(schedule.removeAsync(jobId))
                .compose(ignored -> toFuture(redis.getMap(stateKey(jobId), StringCodec.INSTANCE).deleteAsync()))
                .mapEmpty();
    }

    private Void pollJobs() {
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
                // Leave nextJobTime <= now to continue checking for available slots.
                log.info("Parallel job limit reached ({}), deferring polling", settings.getMaxParallelJobs());
                break;
            }

            String jobId = scoredEntry.getValue();
            if (!inFlightJobIds.add(jobId)) {
                // Already polling
                continue;
            }

            try {
                ClaimResult claimResult = claim(scoredEntry);
                if (claimResult == null) {
                    inFlightJobIds.remove(jobId);
                    continue;
                }
                BackgroundJobService.Poller poller = jobPollerProvider.apply(jobId);
                if (poller == null) {
                    log.info("Background job {} record or mapping not found, stopping polling", jobId);
                    complete(jobId, claimResult.owner());
                    inFlightJobIds.remove(jobId);
                    continue;
                }

                poller.poll()
                        .compose(
                                finished -> handleSuccess(finished, jobId, claimResult),
                                error -> handleFailure(error, jobId, claimResult, poller::fail))
                        .eventually(() -> Future.succeededFuture(inFlightJobIds.remove(jobId)));
            } catch (Throwable e) {
                log.warn("Failed to process background job {}", jobId, e);
                inFlightJobIds.remove(jobId);
            }
        }

        return null;
    }

    private Future<Void> handleSuccess(Boolean finished, String jobId, ClaimResult claimResult) {
        if (finished) {
            return complete(jobId, claimResult.owner());
        }
        long backoff = (long) Math.min(
                settings.getInitialPollIntervalMs() * Math.pow(settings.getPollBackoffFactor(), claimResult.attempts()),
                settings.getMaxPollIntervalMs());
        long nextPollTime = System.currentTimeMillis() + backoff;
        return reschedule(jobId, claimResult.owner(), claimResult.attempts() + 1, 0, nextPollTime)
                .onSuccess(ignore -> nextJobTime.accumulateAndGet(nextPollTime, Math::min));
    }

    private Future<Void> handleFailure(Throwable error, String jobId, ClaimResult claimResult, Supplier<Future<Void>> failureHandler) {
        int newErrors = claimResult.errors() + 1;
        if (newErrors >= settings.getMaxSequentialPollFailures()) {
            log.error("Background job {} exceeded max poll failures, giving up", jobId, error);
            return complete(jobId, claimResult.owner())
                    .eventually(failureHandler);
        }
        log.warn("Poll failed for background job {} ({}/{})", jobId, newErrors, settings.getMaxSequentialPollFailures(), error);
        long nextPollTime = System.currentTimeMillis() + settings.getInitialPollIntervalMs();
        return reschedule(jobId, claimResult.owner(), claimResult.attempts(), newErrors, nextPollTime)
                .onSuccess(ignore -> nextJobTime.accumulateAndGet(nextPollTime, Math::min));
    }

    @Nullable
    private ClaimResult claim(ScoredEntry<String> entry) {
        long leaseUntil = System.currentTimeMillis() + settings.getLeaseTimeoutMs();
        long owner = ThreadLocalRandom.current().nextLong();

        String jobId = entry.getValue();
        List<Object> result = script.eval(
                RScript.Mode.READ_WRITE,
                """
                        local score = redis.call('ZSCORE', KEYS[1], ARGV[3])
                        if score == false or tonumber(score) > tonumber(ARGV[1]) then
                            return {}
                        end

                        redis.call('HSET', KEYS[2], 'owner', ARGV[4])
                        redis.call('ZADD', KEYS[1], tonumber(ARGV[2]), ARGV[3])

                        local attempts = redis.call('HGET', KEYS[2], 'attempts')
                        local errors = redis.call('HGET', KEYS[2], 'errors')

                        return {attempts or '0', errors or '0'}
                        """,
                RScript.ReturnType.MULTI,
                List.of(scheduleKey, stateKey(jobId)),
                String.valueOf(entry.getScore()),
                String.valueOf(leaseUntil),
                jobId,
                String.valueOf(owner));

        if (result.isEmpty()) {
            return null;
        }

        int attempts = Integer.parseInt((String) result.get(0));
        int errors = Integer.parseInt((String) result.get(1));

        return new ClaimResult(owner, attempts, errors);
    }

    private Future<Void> reschedule(String jobId, long owner, int attempts, int errors, long nextPollTime) {
        return toFuture(script.<Long>evalAsync(
                RScript.Mode.READ_WRITE,
                """
                        local owner = redis.call('HGET', KEYS[2], 'owner')
                        if owner ~= ARGV[2] then
                            return 0
                        end
                        
                        redis.call('ZADD', KEYS[1], tonumber(ARGV[3]), ARGV[1])
                        redis.call('HSET', KEYS[2], 'attempts', ARGV[4], 'errors', ARGV[5])

                        return 1
                        """,
                RScript.ReturnType.INTEGER,
                List.of(scheduleKey, stateKey(jobId)),
                jobId,
                String.valueOf(owner),
                String.valueOf(nextPollTime),
                String.valueOf(attempts),
                String.valueOf(errors)))
                .map(result -> {
                    if (result == 1L) {
                        log.debug("Background job {} rescheduled", jobId);
                    } else {
                        log.warn("Background job {} reschedule skipped (owner mismatch)", jobId);
                    }

                    return null;
                });
    }

    private Future<Void> complete(String dialId, long owner) {
        return toFuture(script.<Long>evalAsync(
                RScript.Mode.READ_WRITE,
                """
                        local owner = redis.call('HGET', KEYS[2], 'owner')
                        if owner ~= false and owner ~= ARGV[2] then
                            return 0
                        end

                        redis.call('ZREM', KEYS[1], ARGV[1])
                        redis.call('DEL', KEYS[2])

                        return 1
                        """,
                RScript.ReturnType.INTEGER,
                List.of(scheduleKey, stateKey(dialId)),
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

    private <T> Future<T> toFuture(RFuture<T> rf) {
        return Future.fromCompletionStage(rf.toCompletableFuture(), vertx.getOrCreateContext());
    }

    private String stateKey(String dialId) {
        return "background_job_state:" + keyTag + ":" + dialId;
    }

    @VisibleForTesting
    Void scan() {
        log.debug("Scanning for unscheduled jobs");
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
                                jobExpirer.accept(dialId);
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


    private record ClaimResult(long owner, int attempts, int errors) {
    }
}
