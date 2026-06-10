package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.BackgroundJobRecord;
import com.epam.aidial.core.server.data.ResponseMapping;
import com.epam.aidial.core.server.limiter.RateLimiter;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.token.TokenStatsTracker;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.util.ResponseIdUtil;
import com.epam.aidial.core.storage.blobstore.BlobStorageUtil;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.util.RedisUtil;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RFuture;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@Slf4j
public class BackgroundJobService {
    public static final long POLL_INTERVAL_MS = TimeUnit.SECONDS.toMillis(10);
    private static final long LEASE_TTL_MS = TimeUnit.SECONDS.toMillis(60);
    private static final int MAX_SEQUENTIAL_POLL_FAILURES = 10;
    private static final long DEFAULT_JOB_TTL_MS = TimeUnit.DAYS.toMillis(1);

    private final ConcurrentHashMap<String, Lease> streamingLeases = new ConcurrentHashMap<>();

    private final long pollIntervalMs;
    private final Vertx vertx;
    private final RedissonClient redis;
    private final String prefix;
    private final Supplier<String> generator;
    private final String jobIndexKey;
    private final LockService lockService;
    private final ConfigStore configStore;
    private final ApiKeyStore apiKeyStore;
    private final RateLimiter rateLimiter;
    private final TokenStatsTracker tokenStatsTracker;
    private final ResponseMappingService responseMappingService;
    private final BackgroundJobPoller poller;

    public BackgroundJobService(
            Vertx vertx,
            RedissonClient redis,
            String prefix,
            Supplier<String> generator,
            LockService lockService,
            ConfigStore configStore,
            ApiKeyStore apiKeyStore,
            RateLimiter rateLimiter,
            TokenStatsTracker tokenStatsTracker,
            ResponseMappingService responseMappingService,
            BackgroundJobPoller poller,
            long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
        this.vertx = vertx;
        this.redis = redis;
        this.prefix = prefix;
        this.generator = generator;
        this.jobIndexKey = "background_job_index:" + BlobStorageUtil.toStoragePath(prefix, ResponseIdUtil.BACKGROUND_JOB_BUCKET_LOCATION);
        this.lockService = lockService;
        this.configStore = configStore;
        this.apiKeyStore = apiKeyStore;
        this.rateLimiter = rateLimiter;
        this.tokenStatsTracker = tokenStatsTracker;
        this.responseMappingService = responseMappingService;
        this.poller = poller;
    }

    public void init() {
        resumeActiveJobs();
    }

    public Future<String> saveJob(ProxyContext context) {
        String jobId = context.getResponseId();
        BackgroundJobRecord record = BackgroundJobRecord.builder()
                .dialResponseId(context.getResponseId())
                .perRequestKey(context.getProxyApiKeyData().getPerRequestKey())
                .traceId(context.getTraceId())
                .spanId(context.getSpanId())
                .createdAt(System.currentTimeMillis())
                .streaming(context.isStreamingRequest())
                .build();
        return persistRecordAsync(jobId, record)
                .map(jobId);
    }

    public Future<Boolean> isJobActive(String dialResponseId) {
        return toFuture(redis.<String>getBucket(toRedisKey(dialResponseId), StringCodec.INSTANCE)
                .isExistsAsync());
    }

    public void submit(String id) {
        addToQueue(id)
                .onSuccess(ignored -> startPolling(id))
                .onFailure(error -> log.warn("Failed to add background job {} to queue", id, error));
    }

    public Future<Void> startStreamingJob(String jobId) {
        return addToQueue(jobId)
                .compose(ignored -> tryAcquireLeaseAsync(jobId))
                .map(lease -> {
                    if (lease != null) {
                        Lease oldLease = streamingLeases.put(jobId, lease);
                        if (oldLease != null) {
                            log.warn("Background job {} already has an active lease, releasing the old lease", jobId);
                            oldLease.release()
                                    .onFailure(e -> log.warn("Failed to release old lease for background job {}", jobId, e));
                        }
                    }
                    return null;
                });
    }

    public Future<Boolean> cancelStreamingJob(String jobId) {
        Lease lease = streamingLeases.remove(jobId);
        return deleteRecordAsync(jobId)
                .compose(deleted -> {
                    if (!deleted) {
                        log.info("Streaming job {} record already deleted, skipping completion processing", jobId);
                        return Future.succeededFuture(false);
                    }
                    return removeFromQueue(jobId)
                            .recover(e -> {
                                log.warn("Failed to remove streaming job {} from queue", jobId, e);
                                return Future.succeededFuture();
                            })
                            .map(true);
                })
                .eventually(() -> lease != null ? lease.release() : Future.succeededFuture());
    }

    private void startPolling(String id) {
        tryAcquireLeaseAsync(id)
                .onSuccess(lease -> {
                    if (lease == null) {
                        log.info("Background job {} lease held by another instance, skipping polling", id);
                        return;
                    }
                    loadRecordAsync(id)
                            .compose(record -> {
                                if (record == null) {
                                    return removeFromQueue(id).mapEmpty();
                                }
                                return vertx.executeBlocking(() -> responseMappingService.getMapping(id))
                                        .compose(mapping -> {
                                            if (mapping == null) {
                                                return Future.failedFuture("Response mapping not found for DIAL response ID " + record.getDialResponseId());
                                            }

                                            Promise<TokenUsage> done = Promise.promise();
                                            AtomicInteger failureCount = new AtomicInteger();
                                            scheduleNextPoll(id, mapping, failureCount, done);
                                            return done.future()
                                                    .compose(usage ->
                                                            processResult(new PollingResult(id, record, mapping, usage)));
                                        });
                            })
                            .eventually(lease::release)
                            .onFailure(error -> log.warn("Failed to do polling for background job {}", id, error));
                })
                .onFailure(error -> log.warn("Failed to acquire lease for background job {}", id, error));
    }

    private Future<Boolean> processResult(PollingResult result) {
        return deleteRecordAsync(result.jobId())
                .compose(deleted -> {
                    if (deleted) {
                        onJobCompleted(result);
                        return removeFromQueue(result.jobId());
                    }

                    log.info("Background job {} record already deleted, skipping completion processing", result.jobId());
                    return Future.succeededFuture();
                });
    }

    private void scheduleNextPoll(
            String id, ResponseMapping mapping, AtomicInteger failureCount, Promise<TokenUsage> done) {
        vertx.setTimer(pollIntervalMs, ignored -> poller.poll(mapping)
                .onSuccess(usage -> {
                    if (usage == null) {
                        failureCount.set(0);
                        scheduleNextPoll(id, mapping, failureCount, done);
                    } else {
                        done.tryComplete(usage);
                    }
                })
                .onFailure(error -> {
                    int n = failureCount.incrementAndGet();
                    if (n < MAX_SEQUENTIAL_POLL_FAILURES) {
                        log.warn("Poll failed for background job {} ({}/{})", id, n, MAX_SEQUENTIAL_POLL_FAILURES, error);
                        scheduleNextPoll(id, mapping, failureCount, done);
                    } else {
                        log.error("Background job {} exceeded max poll failures, giving up", id, error);
                        done.tryComplete(null);
                    }
                }));
    }

    private void onJobCompleted(PollingResult result) {
        BackgroundJobRecord jobRecord = result.jobRecord();
        ResponseMapping responseMapping = result.responseMapping();
        TokenUsage usage = result.usage();
        Config config = configStore.get();
        Deployment deployment = config.selectDeployment(responseMapping.getDeploymentName());

        Future<Void> tokenFuture = Future.succeededFuture();
        if (usage != null && jobRecord.getTraceId() != null && jobRecord.getSpanId() != null) {
            tokenFuture = tokenStatsTracker.updateStats(jobRecord.getTraceId(), jobRecord.getSpanId(), usage)
                    .compose(ignored -> tokenStatsTracker.endRootSpan(jobRecord.getTraceId()));
        }

        Future<Void> rateLimitFuture = Future.succeededFuture();
        if (deployment instanceof Model && usage != null) {
            String bucketLocation = responseMapping.getInitiatorBucket();
            rateLimitFuture = rateLimiter.increase(deployment, bucketLocation, usage, null, null)
                    .recover(error -> {
                        log.warn("Failed to update rate limit for background job {}", result.jobId(), error);
                        return Future.succeededFuture();
                    });
        }

        Future.all(tokenFuture, rateLimitFuture)
                .eventually(() -> invalidatePerRequestKey(jobRecord))
                .onFailure(error ->
                        log.error("Failed to finalize background job {}", result.jobId(), error));
    }

    private void resumeActiveJobs() {
        log.info("Scanning for active background jobs to resume");
        toFuture(redis.<String>getSet(jobIndexKey, StringCodec.INSTANCE).readAllAsync())
                .onSuccess(jobIds -> {
                    for (String jobId : jobIds) {
                        loadRecordAsync(jobId)
                                .onSuccess(job -> {
                                    if (job == null) {
                                        redis.<String>getSet(jobIndexKey, StringCodec.INSTANCE).removeAsync(jobId);
                                        return;
                                    }
                                    // TODO: resume streaming request
                                    startPolling(jobId);
                                    log.info("Resumed polling background job {}", jobId);
                                })
                                .onFailure(e -> log.warn("Failed to resume background job {}", jobId, e));
                    }
                })
                .onFailure(e -> log.warn("Failed to scan for active background jobs", e));
    }

    private Future<Boolean> addToQueue(String id) {
        return toFuture(redis.<String>getSet(jobIndexKey, StringCodec.INSTANCE).addAsync(id));
    }

    private Future<Boolean> removeFromQueue(String id) {
        return toFuture(redis.<String>getSet(jobIndexKey, StringCodec.INSTANCE).removeAsync(id));
    }

    private Future<BackgroundJobRecord> loadRecordAsync(String jobId) {
        return toFuture(redis.<String>getBucket(toRedisKey(jobId), StringCodec.INSTANCE).getAsync())
                .map(json -> ProxyUtil.convertToObject(json, BackgroundJobRecord.class));
    }

    private Future<Void> persistRecordAsync(String id, BackgroundJobRecord record) {
        String json = ProxyUtil.convertToString(record);
        return toFuture(redis.<String>getBucket(toRedisKey(id), StringCodec.INSTANCE)
                .setAsync(json, DEFAULT_JOB_TTL_MS, TimeUnit.MILLISECONDS));
    }

    private Future<Boolean> deleteRecordAsync(String jobId) {
        return toFuture(redis.<String>getBucket(toRedisKey(jobId), StringCodec.INSTANCE).deleteAsync());
    }

    private String toRedisKey(String jobId) {
        ResourceDescriptor resource = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.BACKGROUND_JOB, ResponseIdUtil.BACKGROUND_JOB_BUCKET,
                ResponseIdUtil.BACKGROUND_JOB_BUCKET_LOCATION, jobId);
        return RedisUtil.redisKey(resource, prefix);
    }

    private Future<Lease> tryAcquireLeaseAsync(String jobId) {
        String key = "background_job_poll:" + jobId;
        String lockId = generator.get();
        return toFuture(lockService.tryCreateClaimAsync(key, lockId, LEASE_TTL_MS))
                .map(remaining -> {
                    if (remaining != 0L) {
                        return null;
                    }
                    long timerId = vertx.setPeriodic(LEASE_TTL_MS / 2, id -> {
                        toFuture(lockService.tryUpdateClaimAsync(key, lockId, LEASE_TTL_MS))
                                .onSuccess(renewed -> {
                                    if (!renewed) {
                                        log.warn("Lease lost for background job {}", jobId);
                                        vertx.cancelTimer(id);
                                    }
                                })
                                .onFailure(e -> {
                                    log.warn("Lease renewal failed for background job {}", jobId, e);
                                    vertx.cancelTimer(id);
                                });
                    });
                    return () -> {
                        vertx.cancelTimer(timerId);
                        return toFuture(lockService.releaseClaimAsync(key, lockId));
                    };
                });
    }

    private <T> Future<T> toFuture(RFuture<T> rf) {
        return Future.fromCompletionStage(rf.toCompletableFuture(), vertx.getOrCreateContext());
    }

    private Future<Boolean> invalidatePerRequestKey(BackgroundJobRecord record) {
        String key = record.getPerRequestKey();
        if (key == null) {
            return Future.succeededFuture(true);
        }
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey(key);
        return apiKeyStore.invalidatePerRequestApiKey(apiKeyData);
    }

    private record PollingResult(
            String jobId,
            BackgroundJobRecord jobRecord,
            ResponseMapping responseMapping,
            TokenUsage usage) {
    }

    @FunctionalInterface
    private interface Lease {
        Future<Boolean> release();
    }
}
