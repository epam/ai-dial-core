package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.BackgroundJobRecord;
import com.epam.aidial.core.server.data.ResponseMapping;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.token.TokenStatsTracker;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResponseIdUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.blobstore.BlobStorageUtil;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.data.NodeType;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
public class BackgroundJobService {

    private static final int PAGE_SIZE = 1000;

    private final String prefix;
    private final Settings settings;
    private final Vertx vertx;
    private final RedissonClient redis;
    private final ResourceService resourceService;
    private final AsyncTaskExecutor taskExecutor;
    private final ConfigStore configStore;
    private final ApiKeyStore apiKeyStore;
    private final TokenStatsTracker tokenStatsTracker;
    private final ResponseMappingService responseMappingService;
    private final BackgroundJobPoller poller;
    private final RScript script;
    private final RScoredSortedSet<String> schedule;
    private final String scheduleKey;
    private final Set<String> inFlightJobIds = ConcurrentHashMap.newKeySet();

    public BackgroundJobService(
            Vertx vertx,
            RedissonClient redis,
            String prefix,
            ResourceService resourceService,
            AsyncTaskExecutor taskExecutor,
            ConfigStore configStore,
            ApiKeyStore apiKeyStore,
            TokenStatsTracker tokenStatsTracker,
            ResponseMappingService responseMappingService,
            BackgroundJobPoller poller,
            Settings settings) {
        this.prefix = prefix;
        this.settings = settings;
        this.vertx = vertx;
        this.redis = redis;
        this.resourceService = resourceService;
        this.taskExecutor = taskExecutor;
        this.configStore = configStore;
        this.apiKeyStore = apiKeyStore;
        this.tokenStatsTracker = tokenStatsTracker;
        this.responseMappingService = responseMappingService;
        this.poller = poller;
        this.script = redis.getScript(StringCodec.INSTANCE);
        this.scheduleKey = "background_job_schedule:" + BlobStorageUtil.toStoragePath(prefix, "queue");
        this.schedule = redis.getScoredSortedSet(scheduleKey, StringCodec.INSTANCE);
    }

    public void init() {
        schedulePoll();
        vertx.setPeriodic(0, settings.getScanIntervalMs(), id -> taskExecutor.submit(this::scan));
    }

    private void schedulePoll() {
        vertx.setTimer(settings.getPollIntervalMs(), id -> tryClaimAndPoll().onComplete(ignored -> schedulePoll()));
    }

    public Future<Void> saveJob(String dialId, BackgroundJobRecord record) {
        String json = ProxyUtil.convertToString(record);
        ResourceDescriptor descriptor = ResponseIdUtil.getBackgroundJobDescriptor(dialId);
        long now = System.currentTimeMillis();
        return taskExecutor.<Void>submit(() -> {
            resourceService.putResource(descriptor, json, EtagHeader.NEW_ONLY);
            return null;
        })
        .compose(ignored -> toFuture(schedule.addAsync(now, dialId)))
        .mapEmpty();
    }

    public Future<Boolean> isJobActive(String dialId) {
        return taskExecutor.submit(() ->
                resourceService.getResourceMetadata(ResponseIdUtil.getBackgroundJobDescriptor(dialId)) != null);
    }

    public Future<Boolean> finishStreamingJob(String dialId) {
        return taskExecutor.submit(() -> resourceService.deleteResource(ResponseIdUtil.getBackgroundJobDescriptor(dialId), EtagHeader.ANY))
                .compose(deleted -> {
                    if (!deleted) {
                        log.info("Streaming job {} record already deleted, skipping completion processing", dialId);
                        return Future.succeededFuture(false);
                    }
                    return forceRemoveFromRedis(dialId)
                            .recover(e -> {
                                log.warn("Failed to remove streaming job {} from Redis schedule", dialId, e);
                                return Future.succeededFuture();
                            })
                            .map(true);
                });
    }

    public Future<Void> tryCompleteOnGet(String dialId, ResponseMapping mapping, ResponsesApiClient.TerminalResult result) {
        if (result == null) {
            return Future.succeededFuture();
        }
        TokenUsage usage = result.usage();
        return taskExecutor.submit(() -> {
            BackgroundJobRecord record = loadBackgroundJobRecord(dialId);
            if (record == null) {
                return null;
            }
            boolean deleted = resourceService.deleteResource(ResponseIdUtil.getBackgroundJobDescriptor(dialId), EtagHeader.ANY);
            return deleted ? record : null;
        })
        .compose(record -> {
            if (record == null) {
                return Future.succeededFuture();
            }
            return forceRemoveFromRedis(dialId)
                    .compose(ignored -> processResult(dialId, record, mapping, usage));
        });
    }

    public void startPolling(String dialId) {
        long nextPollTime = System.currentTimeMillis() + settings.getPollIntervalMs();
        toFuture(schedule.addIfAbsentAsync(nextPollTime, dialId))
                .onFailure(e -> log.warn("Failed to schedule background job {}", dialId, e));
    }

    private Future<Void> tryClaimAndPoll() {
        if (inFlightJobIds.size() >= settings.getMaxParallelJobs()) {
            return Future.succeededFuture();
        }
        long now = System.currentTimeMillis();
        long owner = ThreadLocalRandom.current().nextLong();

        return nextJob(now)
                .compose(dialId -> {
                    if (dialId == null) {
                        return Future.succeededFuture();
                    }
                    long leaseUntil = System.currentTimeMillis() + settings.getLeaseTimeoutMs();
                    return executeClaim(stateKey(dialId), now, leaseUntil, dialId, owner)
                            .compose(claimResult -> {
                                if (claimResult == null || claimResult.isEmpty()) {
                                    return tryClaimAndPoll();
                                }
                                int attempts = Integer.parseInt((String) claimResult.get(0));
                                int errors = Integer.parseInt((String) claimResult.get(1));
                                if (inFlightJobIds.add(dialId)) {
                                    poll(dialId, owner, attempts, errors)
                                            .onComplete(ignored -> inFlightJobIds.remove(dialId))
                                            .onFailure(e -> log.warn("Failed to process background job {}", dialId, e));
                                    return tryClaimAndPoll();
                                }
                                return Future.succeededFuture();
                            });
                })
                .onFailure(e -> log.warn("Failed to claim background job", e));
    }

    private Future<String> nextJob(long now) {
        return toFuture(schedule.valueRangeAsync(
                Double.NEGATIVE_INFINITY, true, (double) now, true, 0, 1))
                .flatMap(candidates -> {
                    if (candidates == null || candidates.isEmpty()) {
                        return Future.succeededFuture(null);
                    }
                    if (candidates.size() != 1) {
                        return Future.failedFuture("Multiple candidates returned from schedule: " + candidates);
                    }
                    return Future.succeededFuture(candidates.iterator().next());
                });
    }

    private Map.Entry<BackgroundJobRecord, ResponseMapping> loadJobData(String dialId) {
        BackgroundJobRecord record = loadBackgroundJobRecord(dialId);
        if (record == null) {
            return null;
        }
        ResponseMapping mapping = responseMappingService.getMapping(dialId);
        if (mapping == null) {
            log.warn("Missing response mapping for background job {}", dialId);
            return null;
        }
        return Map.entry(record, mapping);
    }

    private Future<Void> poll(String dialId, long owner, int attempts, int errors) {
        return taskExecutor.submit(() -> loadJobData(dialId))
                .compose(pair -> {
                    String stateKey = stateKey(dialId);
                    if (pair == null) {
                        log.info("Background job {} record or mapping not found, stopping polling", dialId);
                        return executeComplete(dialId, stateKey, owner);
                    }
                    BackgroundJobRecord record = pair.getKey();
                    ResponseMapping mapping = pair.getValue();
                    return poller.poll(mapping)
                            .compose(
                                    result -> {
                                        if (result == null) {
                                            long backoff = (long) Math.min(
                                                    settings.getPollIntervalMs() * Math.pow(settings.getPollBackoffFactor(), attempts),
                                                    settings.getMaxPollIntervalMs());
                                            long nextPollTime = System.currentTimeMillis() + backoff;
                                            return executeReschedule(dialId, stateKey, owner, nextPollTime, attempts + 1, 0);
                                        }
                                        return completeAndProcess(dialId, stateKey, owner, record, mapping, result.usage());
                                    },
                                    error -> {
                                        int newErrors = errors + 1;
                                        if (newErrors >= settings.getMaxSequentialPollFailures()) {
                                            log.error("Background job {} exceeded max poll failures, giving up", dialId, error);
                                            return completeAndProcess(dialId, stateKey, owner, record, mapping, null);
                                        }
                                        log.warn("Poll failed for background job {} ({}/{})", dialId, newErrors,
                                                settings.getMaxSequentialPollFailures(), error);
                                        long nextPollTime = System.currentTimeMillis() + settings.getPollIntervalMs();
                                        return executeReschedule(dialId, stateKey, owner, nextPollTime, attempts, newErrors);
                                    });
                });
    }

    private BackgroundJobRecord loadBackgroundJobRecord(String dialId) {
        String json = resourceService.getResource(ResponseIdUtil.getBackgroundJobDescriptor(dialId));
        return ProxyUtil.convertToObject(json, BackgroundJobRecord.class);
    }

    private Future<Void> completeAndProcess(
            String dialId,
            String stateKey,
            long owner,
            BackgroundJobRecord record,
            ResponseMapping mapping,
            TokenUsage usage) {
        return taskExecutor.submit(() -> resourceService.deleteResource(ResponseIdUtil.getBackgroundJobDescriptor(dialId), EtagHeader.ANY))
                .compose(deleted -> {
                    Future<Void> redisCleanup = executeComplete(dialId, stateKey, owner);
                    if (!deleted) {
                        log.info("Background job {} already completed by another handler, skipping processing", dialId);
                        return redisCleanup;
                    }
                    return redisCleanup.eventually(() -> processResult(dialId, record, mapping, usage));
                });
    }

    private Future<List<Object>> executeClaim(String stateKey, long now, long leaseUntil, String dialId, long owner) {
        return toFuture(script.evalAsync(
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
                List.of(scheduleKey, stateKey),
                String.valueOf(now),
                String.valueOf(leaseUntil),
                dialId,
                String.valueOf(owner)));
    }

    private Future<Void> executeReschedule(
            String dialId, String stateKey, long owner, long nextPollTime, int attempts, int errors) {
        return toFuture(script.<Long>evalAsync(
                RScript.Mode.READ_WRITE,
                """
                        local owner = redis.call('HGET', KEYS[2], 'owner')
                        if owner ~= ARGV[2] then
                            return 0
                        end
                        redis.call('ZADD', KEYS[1], tonumber(ARGV[3]), ARGV[1])
                        redis.call('HSET', KEYS[2], 'attempts', ARGV[4])
                        redis.call('HSET', KEYS[2], 'errors', ARGV[5])
                        return 1
                        """,
                RScript.ReturnType.INTEGER,
                List.of(scheduleKey, stateKey),
                dialId,
                String.valueOf(owner),
                String.valueOf(nextPollTime),
                String.valueOf(attempts),
                String.valueOf(errors)))
                .map(result -> {
                    if (result == 1L) {
                        log.debug("Background job {} rescheduled, attempts={}, errors={}", dialId, attempts, errors);
                    } else {
                        log.warn("Background job {} reschedule skipped (owner mismatch)", dialId);
                    }

                    return null;
                });
    }

    private Future<Void> executeComplete(String dialId, String stateKey, long owner) {
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
                List.of(scheduleKey, stateKey),
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

    private Future<Void> forceRemoveFromRedis(String dialId) {
        return toFuture(schedule.removeAsync(dialId))
                .compose(ignored -> toFuture(redis.getMap(stateKey(dialId), StringCodec.INSTANCE).deleteAsync()))
                .mapEmpty();
    }

    private Future<Void> processResult(
            String responseId, BackgroundJobRecord jobRecord, ResponseMapping responseMapping, TokenUsage usage) {
        Config config = configStore.get();
        Deployment deployment = config.selectDeployment(responseMapping.getDeploymentName());

        apiKeyStore.getApiKeyData(jobRecord.perRequestKey(), null)
                .recover(e -> Future.succeededFuture(null))
                .onSuccess(apiKeyData -> {
                    String traceId = apiKeyData != null ? apiKeyData.getTraceId() : null;
                    String spanId = apiKeyData != null ? apiKeyData.getSpanId() : null;

                    Future<Void> future = Future.succeededFuture();
                    if (deployment instanceof Model && usage != null) {
                        future = tokenStatsTracker.collectUsage(
                                deployment, responseMapping.getInitiatorBucket(), usage, null, null, traceId, spanId);
                    }
                    if (traceId != null && Boolean.TRUE.equals(jobRecord.isRootSpan())) {
                        future = future.compose(ignored -> tokenStatsTracker.endSpan(traceId));
                    }
                    future.eventually(() -> invalidatePerRequestKey(jobRecord.perRequestKey()))
                            .onFailure(error -> log.error("Failed to finalize background job {}", responseId, error));
                });
        return Future.succeededFuture();
    }

    private void expireJob(String dialId) {
        Map.Entry<BackgroundJobRecord, ResponseMapping> pair = loadJobData(dialId);
        if (pair == null) {
            return;
        }
        boolean deleted = resourceService.deleteResource(ResponseIdUtil.getBackgroundJobDescriptor(dialId), EtagHeader.ANY);
        if (deleted) {
            processResult(dialId, pair.getKey(), pair.getValue(), null)
                    .onFailure(e -> log.warn("BackgroundJobService: failed to finalize expired job {}", dialId, e));
        }
    }

    private Future<Boolean> invalidatePerRequestKey(String key) {
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey(key);
        return apiKeyStore.invalidatePerRequestApiKey(apiKeyData);
    }

    private <T> Future<T> toFuture(RFuture<T> rf) {
        return Future.fromCompletionStage(rf.toCompletableFuture(), vertx.getOrCreateContext());
    }

    private String stateKey(String dialId) {
        return "background_job_state:" + BlobStorageUtil.toStoragePath(prefix, dialId);
    }

    @VisibleForTesting
    Void scan() {
        log.info("BackgroundJobService: scanning for unscheduled jobs");
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
                            String dialId = item.getName();
                            if (item instanceof ResourceItemMetadata meta
                                    && meta.getCreatedAt() != null
                                    && now - meta.getCreatedAt() >= settings.getJobTtlMs()) {
                                log.warn("BackgroundJobService: expiring job {}, age={}ms", dialId, now - meta.getCreatedAt());
                                expireJob(dialId);
                            } else {
                                schedule.addIfAbsent(now, dialId);
                            }
                        }
                    }
                }
                token = folder.getNextToken();
            } while (token != null);
        } catch (Throwable e) {
            log.warn("BackgroundJobService: scan failed", e);
        }

        return null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class Settings {
        long pollIntervalMs = TimeUnit.SECONDS.toMillis(10);
        long maxPollIntervalMs = TimeUnit.MINUTES.toMillis(5);
        double pollBackoffFactor = 2.0;
        int maxSequentialPollFailures = 10;
        long jobTtlMs = TimeUnit.DAYS.toMillis(1);
        long leaseTimeoutMs = TimeUnit.MINUTES.toMillis(5);
        int maxParallelJobs = 100;
        long scanIntervalMs = TimeUnit.MINUTES.toMillis(10);
    }
}
