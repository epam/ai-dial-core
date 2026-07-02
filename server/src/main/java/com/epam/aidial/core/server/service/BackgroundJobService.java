package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.BackgroundJobRecord;
import com.epam.aidial.core.server.data.ResponseMapping;
import com.epam.aidial.core.server.limiter.RateLimiter;
import com.epam.aidial.core.server.log.AnalyticsLogContext;
import com.epam.aidial.core.server.log.LogStore;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.token.TokenStatsTracker;
import com.epam.aidial.core.server.upstream.UpstreamRouteProvider;
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
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RFuture;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.ScoredEntry;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;

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
    private final RateLimiter rateLimiter;
    private final TokenStatsTracker tokenStatsTracker;
    private final ResponseMappingService responseMappingService;
    private final UpstreamRouteProvider upstreamRouteProvider;
    private final ResponsesApiClient client;
    private final LogStore logStore;
    private final RScript script;
    private final RScoredSortedSet<String> schedule;
    private final String scheduleKey;
    private final Set<String> inFlightJobIds = ConcurrentHashMap.newKeySet();
    private final AtomicLong nextJobTime = new AtomicLong(Long.MAX_VALUE);

    public BackgroundJobService(
            Vertx vertx,
            RedissonClient redis,
            String prefix,
            ResourceService resourceService,
            AsyncTaskExecutor taskExecutor,
            ConfigStore configStore,
            ApiKeyStore apiKeyStore,
            RateLimiter rateLimiter,
            TokenStatsTracker tokenStatsTracker,
            ResponseMappingService responseMappingService,
            UpstreamRouteProvider upstreamRouteProvider,
            ResponsesApiClient client,
            Settings settings,
            LogStore logStore) {
        this.prefix = prefix;
        this.settings = settings;
        this.vertx = vertx;
        this.redis = redis;
        this.resourceService = resourceService;
        this.taskExecutor = taskExecutor;
        this.configStore = configStore;
        this.apiKeyStore = apiKeyStore;
        this.rateLimiter = rateLimiter;
        this.tokenStatsTracker = tokenStatsTracker;
        this.responseMappingService = responseMappingService;
        this.upstreamRouteProvider = upstreamRouteProvider;
        this.client = client;
        this.logStore = logStore;
        this.script = redis.getScript(StringCodec.INSTANCE);
        this.scheduleKey = "background_job_schedule:" + BlobStorageUtil.toStoragePath(prefix, "queue");
        this.schedule = redis.getScoredSortedSet(scheduleKey, StringCodec.INSTANCE);
    }

    public void init() {
        AtomicBoolean polling = new AtomicBoolean(false);
        vertx.setPeriodic(0, 1, id -> {
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

    public Future<Void> saveJob(String dialId, BackgroundJobRecord record) {
        String json = ProxyUtil.convertToString(record);
        ResourceDescriptor descriptor = ResponseIdUtil.getBackgroundJobDescriptor(dialId);
        return taskExecutor.submit(() -> resourceService.putResource(descriptor, json, EtagHeader.NEW_ONLY))
                .onSuccess(ignore -> schedule(dialId, System.currentTimeMillis() + settings.getInitialPollIntervalMs()))
                .mapEmpty();
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
        return taskExecutor.submit(() ->
                resourceService.hasResource(ResponseIdUtil.getBackgroundJobDescriptor(dialId)));
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
                    .compose(ignored -> processResult(dialId, record, mapping, result));
        });
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
                if (claimResult != null) {
                    poll(dialId, claimResult)
                            .onComplete(ignore -> inFlightJobIds.remove(dialId))
                            .onFailure(e -> log.warn("Failed to process background job {}", dialId, e));
                } else {
                    inFlightJobIds.remove(dialId);
                }
            } catch (Throwable e) {
                log.warn("Failed to process background job {}", dialId, e);
                inFlightJobIds.remove(dialId);
            }
        }

        return null;
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

    @VisibleForTesting
    Future<ResponsesApiClient.TerminalResult> pollMapping(ResponseMapping mapping) {
        Config config = configStore.get();
        Deployment deployment = config.selectDeployment(mapping.getDeploymentName());
        if (deployment == null) {
            return Future.failedFuture("Deployment {} not found");
        }
        if (deployment.getResponsesEndpoint() == null) {
            return Future.failedFuture("Deployment " + deployment.getName() + " does not have a responses endpoint");
        }
        Upstream upstream;
        try {
            upstream = upstreamRouteProvider.get(deployment, null, mapping.getUpstreamKey()).next();
        } catch (Exception e) {
            return Future.failedFuture("Failed to get upstream for deployment " + deployment.getName()
                    + " and upstream key " + mapping.getUpstreamKey() + ": " + e.getMessage());
        }
        String targetUrl = deployment.getResponsesEndpoint() + "/" + mapping.getUpstreamResponseId();
        return client.send(targetUrl, HttpMethod.GET, upstream)
                .compose(response -> {
                    int statusCode = response.statusCode();
                    if (statusCode != 200) {
                        return Future.failedFuture("Unexpected status " + statusCode + " from upstream for background job " + mapping.getUpstreamResponseId());
                    }
                    return response.body().map(ResponsesApiClient::parseTerminalBody);
                });
    }

    private Future<Void> poll(String dialId, ClaimResult claimResult) {
        return taskExecutor.submit(() -> loadJobData(dialId))
                .compose(pair -> {
                    if (pair == null) {
                        log.info("Background job {} record or mapping not found, stopping polling", dialId);
                        return executeComplete(dialId, claimResult.owner);
                    }
                    BackgroundJobRecord record = pair.getKey();
                    ResponseMapping mapping = pair.getValue();
                    return pollMapping(mapping)
                            .compose(
                                    result -> {
                                        if (result == null) {
                                            long backoff = (long) Math.min(
                                                    settings.getInitialPollIntervalMs() * Math.pow(settings.getPollBackoffFactor(), claimResult.attempts),
                                                    settings.getMaxPollIntervalMs());
                                            long nextPollTime = System.currentTimeMillis() + backoff;
                                            return executeReschedule(dialId, claimResult, true, nextPollTime);
                                        }
                                        return completeAndProcess(dialId, claimResult.owner, record, mapping, result);
                                    },
                                    error -> {
                                        int newErrors = claimResult.errors + 1;
                                        if (newErrors >= settings.getMaxSequentialPollFailures()) {
                                            log.error("Background job {} exceeded max poll failures, giving up", dialId, error);
                                            return completeAndProcess(dialId, claimResult.owner, record, mapping, null);
                                        }
                                        log.warn("Poll failed for background job {} ({}/{})", dialId, newErrors,
                                                settings.getMaxSequentialPollFailures(), error);
                                        long nextPollTime = System.currentTimeMillis() + settings.getInitialPollIntervalMs();
                                        return executeReschedule(dialId, claimResult, false, nextPollTime);
                                    });
                });
    }

    private BackgroundJobRecord loadBackgroundJobRecord(String dialId) {
        String json = resourceService.getResource(ResponseIdUtil.getBackgroundJobDescriptor(dialId));
        return ProxyUtil.convertToObject(json, BackgroundJobRecord.class);
    }

    private Future<Void> completeAndProcess(
            String dialId,
            long owner,
            BackgroundJobRecord record,
            ResponseMapping mapping,
            ResponsesApiClient.TerminalResult result) {
        return taskExecutor.submit(() -> resourceService.deleteResource(ResponseIdUtil.getBackgroundJobDescriptor(dialId), EtagHeader.ANY))
                .compose(deleted -> {
                    Future<Void> redisCleanup = executeComplete(dialId, owner);
                    if (!deleted) {
                        log.info("Background job {} already completed by another handler, skipping processing", dialId);
                        return redisCleanup;
                    }
                    return redisCleanup.eventually(() -> processResult(dialId, record, mapping, result));
                });
    }

    @Nullable
    private ClaimResult executeClaim(ScoredEntry<String> entry) {
        long leaseUntil = System.currentTimeMillis() + settings.getLeaseTimeoutMs();
        long owner = ThreadLocalRandom.current().nextLong();
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
                List.of(scheduleKey, stateKey(entry.getValue())),
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

    private Future<Void> executeReschedule(String dialId, ClaimResult claimResult, boolean success, long nextPollTime) {
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
                List.of(scheduleKey, stateKey(dialId)),
                dialId,
                String.valueOf(claimResult.owner),
                String.valueOf(nextPollTime),
                String.valueOf(success ? claimResult.attempts + 1 : claimResult.attempts),
                String.valueOf(success ? 0 : claimResult.errors + 1)))
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

    private Future<Void> forceRemoveFromRedis(String dialId) {
        return toFuture(schedule.removeAsync(dialId))
                .compose(ignored -> toFuture(redis.getMap(stateKey(dialId), StringCodec.INSTANCE).deleteAsync()))
                .mapEmpty();
    }

    private Future<Void> processResult(
            String responseId, BackgroundJobRecord jobRecord, ResponseMapping responseMapping, ResponsesApiClient.TerminalResult result) {
        Config config = configStore.get();
        Deployment deployment = config.selectDeployment(responseMapping.getDeploymentName());

        apiKeyStore.getApiKeyData(jobRecord.perRequestKey(), null)
                .recover(e -> Future.succeededFuture(null))
                .onSuccess(apiKeyData -> {
                    String traceId = apiKeyData != null ? apiKeyData.getTraceId() : null;
                    String spanId = apiKeyData != null ? apiKeyData.getSpanId() : null;

                    Future<Void> future = Future.succeededFuture();
                    if (deployment instanceof Model && result != null && result.usage() != null) {
                        Buffer requestBody = Buffer.buffer(jobRecord.requestBody());
                        future = rateLimiter.increase(
                                deployment, responseMapping.getInitiatorBucket(), result.usage(), requestBody, result.body())
                                .transform(limitResult -> {
                                    if (limitResult.failed()) {
                                        log.warn("Failed to increase limit", limitResult.cause());
                                    }
                                    if (traceId != null && spanId != null) {
                                        return tokenStatsTracker.updateModelStats(traceId, spanId, result.usage());
                                    }
                                    return Future.succeededFuture();
                                });
                    }
                    if (traceId != null && Boolean.TRUE.equals(jobRecord.isRootSpan())) {
                        future = future.compose(ignored -> tokenStatsTracker.endSpan(traceId));
                    }
                    future.eventually(() -> invalidatePerRequestKey(jobRecord.perRequestKey()))
                            .onComplete(ignored -> logStore.save(AnalyticsLogContext.from(jobRecord, result)))
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
                                expireJob(dialId);
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
    }

    private record ClaimResult(long owner, int attempts, int errors) {
    }
}
