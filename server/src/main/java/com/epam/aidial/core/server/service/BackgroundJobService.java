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
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RFuture;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class BackgroundJobService {

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class Settings {
        private long pollIntervalMs = TimeUnit.SECONDS.toMillis(10);
        private int maxSequentialPollFailures = 10;
        private long defaultJobTtlMs = TimeUnit.DAYS.toMillis(1);
    }

    private final long pollIntervalMs;
    private final int maxSequentialPollFailures;
    private final long defaultJobTtlMs;
    private final Vertx vertx;
    private final RedissonClient redis;
    private final String prefix;
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
            LockService lockService,
            ConfigStore configStore,
            ApiKeyStore apiKeyStore,
            RateLimiter rateLimiter,
            TokenStatsTracker tokenStatsTracker,
            ResponseMappingService responseMappingService,
            BackgroundJobPoller poller,
            Settings settings) {
        this.pollIntervalMs = settings.getPollIntervalMs();
        this.maxSequentialPollFailures = settings.getMaxSequentialPollFailures();
        this.defaultJobTtlMs = settings.getDefaultJobTtlMs();
        this.vertx = vertx;
        this.redis = redis;
        this.prefix = prefix;
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

    public Future<Void> saveJob(ProxyContext context) {
        String jobId = context.getResponseId();
        BackgroundJobRecord record = BackgroundJobRecord.builder()
                .perRequestKey(context.getProxyApiKeyData().getPerRequestKey())
                .traceId(context.getTraceId())
                .spanId(context.getSpanId())
                .createdAt(System.currentTimeMillis())
                .streaming(context.isStreamingRequest())
                .build();
        return persistRecordAsync(jobId, record)
                .compose(ignored -> addToQueue(jobId).mapEmpty());
    }

    public Future<Boolean> isJobActive(String dialResponseId) {
        return toFuture(redis.<String>getBucket(toRedisKey(dialResponseId), StringCodec.INSTANCE)
                .isExistsAsync());
    }

    public void submit(String id) {
        startPolling(id);
    }

    public Future<Boolean> cancelStreamingJob(String jobId) {
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
                });
    }

    public Future<Void> tryCompleteOnGet(String dialResponseId, ResponseMapping mapping, ResponsesApiClient.TerminalResult result) {
        if (result == null) {
            return Future.succeededFuture();
        }
        TokenUsage usage = result.usage();
        return loadAndDeleteRecordAsync(dialResponseId)
                .compose(record -> {
                    if (record == null) {
                        return Future.succeededFuture();
                    }
                    return processResult(dialResponseId, record, mapping, usage);
                });
    }

    private void startPolling(String id) {
        loadRecordAsync(id)
                .compose(record -> {
                    if (record == null) {
                        return removeFromQueue(id).mapEmpty();
                    }
                    return vertx.executeBlocking(() -> responseMappingService.getMapping(id))
                            .compose(mapping -> {
                                if (mapping == null) {
                                    return Future.failedFuture("Response mapping not found for background job " + id);
                                }
                                scheduleNextPoll(id, mapping, new AtomicInteger());
                                return Future.succeededFuture();
                            });
                })
                .onFailure(error -> log.warn("Failed to do polling for background job {}", id, error));
    }

    private Future<Void> processResult(String jobId, BackgroundJobRecord jobRecord, ResponseMapping responseMapping, TokenUsage usage) {
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
                        log.warn("Failed to update rate limit for background job {}", jobId, error);
                        return Future.succeededFuture();
                    });
        }

        Future.all(tokenFuture, rateLimitFuture)
                .eventually(() -> invalidatePerRequestKey(jobRecord))
                .onFailure(error ->
                        log.error("Failed to finalize background job {}", jobId, error));
        return removeFromQueue(jobId).mapEmpty();
    }

    private Future<Void> deleteAndProcess(String jobId, BackgroundJobRecord record, ResponseMapping mapping, TokenUsage usage) {
        return deleteRecordAsync(jobId)
                .compose(deleted -> {
                    if (deleted) {
                        processResult(jobId, record, mapping, usage)
                                .onFailure(e -> log.error("Failed to finalize background job {}", jobId, e));
                    } else {
                        log.info("Background job {} record already deleted, skipping completion processing", jobId);
                    }
                    return Future.succeededFuture();
                });
    }

    private void scheduleNextPoll(String id, ResponseMapping mapping, AtomicInteger failureCount) {
        vertx.setTimer(pollIntervalMs, ignored -> {
            LockService.Lock lock = lockService.tryLock("background_job_poll:" + id);
            if (lock == null) {
                scheduleNextPoll(id, mapping, failureCount);
                return;
            }
            loadRecordAsync(id)
                    .compose(record -> {
                        if (record == null) {
                            log.info("Background job {} record not found, stopping polling", id);
                            return Future.succeededFuture();
                        }
                        return poller.poll(mapping)
                                .compose(
                                        result -> {
                                            if (result == null) {
                                                failureCount.set(0);
                                                scheduleNextPoll(id, mapping, failureCount);
                                                return Future.succeededFuture();
                                            }
                                            return deleteAndProcess(id, record, mapping, result.usage());
                                        },
                                        error -> {
                                            int n = failureCount.incrementAndGet();
                                            if (n < maxSequentialPollFailures) {
                                                log.warn("Poll failed for background job {} ({}/{})", id, n, maxSequentialPollFailures, error);
                                                scheduleNextPoll(id, mapping, failureCount);
                                                return Future.succeededFuture();
                                            }
                                            log.error("Background job {} exceeded max poll failures, giving up", id, error);
                                            return deleteAndProcess(id, record, mapping, null);
                                        }
                                );
                    })
                    .onComplete(ignore -> lock.close())
                    .onFailure(error -> {
                        log.warn("Failed to process background job {}", id, error);
                        scheduleNextPoll(id, mapping, failureCount);
                    });
        });
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
                .setAsync(json, defaultJobTtlMs, TimeUnit.MILLISECONDS));
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

    private <T> Future<T> toFuture(RFuture<T> rf) {
        return Future.fromCompletionStage(rf.toCompletableFuture(), vertx.getOrCreateContext());
    }

    private Future<BackgroundJobRecord> loadAndDeleteRecordAsync(String jobId) {
        return toFuture(redis.<String>getBucket(toRedisKey(jobId), StringCodec.INSTANCE).getAndDeleteAsync())
                .map(json -> ProxyUtil.convertToObject(json, BackgroundJobRecord.class));
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

}
