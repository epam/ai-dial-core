package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.encryption.CredentialEncryptionService;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.BackgroundJobRecord;
import com.epam.aidial.core.server.limiter.RateLimiter;
import com.epam.aidial.core.server.log.AnalyticsLogContext;
import com.epam.aidial.core.server.log.LogStore;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.token.TokenStatsTracker;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.token.UsagePerModel;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResponseIdUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.annotations.VisibleForTesting;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
public class BackgroundJobService {
    private final Vertx vertx;
    private final RedissonClient redis;
    private final String prefix;
    private final ResourceService resourceService;
    private final AsyncTaskExecutor taskExecutor;
    private final ConfigStore configStore;
    private final ApiKeyStore apiKeyStore;
    private final RateLimiter rateLimiter;
    private final TokenStatsTracker tokenStatsTracker;
    private final HttpClient httpClient;
    private final LogStore logStore;
    private final CredentialEncryptionService encryptionService;
    private final Settings settings;
    private BackgroundJobScheduler scheduler;

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
            HttpClient httpClient,
            LogStore logStore,
            CredentialEncryptionService encryptionService,
            Settings settings) {
        this.vertx = vertx;
        this.redis = redis;
        this.prefix = prefix;
        this.resourceService = resourceService;
        this.taskExecutor = taskExecutor;
        this.configStore = configStore;
        this.apiKeyStore = apiKeyStore;
        this.rateLimiter = rateLimiter;
        this.tokenStatsTracker = tokenStatsTracker;
        this.httpClient = httpClient;
        this.logStore = logStore;
        this.encryptionService = encryptionService;
        this.settings = settings;
    }

    public void init() {
        scheduler = new BackgroundJobScheduler(vertx, redis, prefix, resourceService, taskExecutor,
                settings, this::jobPoller, this::expireJob);
        scheduler.init();
    }

    public Future<Void> saveJob(String dialId, ProxyContext context) {
        return saveJobRecord(dialId, context)
                .onSuccess(ignore -> scheduler.schedule(dialId, System.currentTimeMillis() + settings.getInitialPollIntervalMs()));
    }

    public Future<Boolean> isJobActive(String dialId) {
        return taskExecutor.submit(() ->
                resourceService.hasResource(ResponseIdUtil.getBackgroundJobDescriptor(dialId)));
    }

    public Future<Boolean> deleteJob(String dialId) {
        return deleteJobRecord(dialId)
                .compose(deleted -> {
                    if (!deleted) {
                        log.info("Streaming job {} record already deleted, skipping completion processing", dialId);
                        return Future.succeededFuture(false);
                    }
                    return scheduler.cancel(dialId)
                            .recover(e -> {
                                log.warn("Failed to remove streaming job {} from Redis schedule", dialId, e);
                                return Future.succeededFuture();
                            })
                            .map(true);
                });
    }

    public Future<Void> tryComplete(String dialId, ResponsesApiClient.TerminalResult result) {
        return taskExecutor.submit(() -> {
            BackgroundJobRecord record = loadJobRecord(dialId);
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
                    return processResult(dialId, record, result)
                            .eventually(() -> scheduler.cancel(dialId));
                });
    }

    public long getJobTtlMs() {
        return settings.getJobTtlMs();
    }

    private Future<Void> saveJobRecord(String dialId, ProxyContext context) {
        ResourceDescriptor descriptor = ResponseIdUtil.getBackgroundJobDescriptor(dialId);
        BackgroundJobRecord record = BackgroundJobRecord.from(context, key -> encryptKey(descriptor, key));
        String json = ProxyUtil.convertToString(record);
        return taskExecutor.submit(() -> resourceService.putResource(descriptor, json, EtagHeader.NEW_ONLY)).mapEmpty();
    }

    private Future<Boolean> deleteJobRecord(String dialId) {
        return taskExecutor.submit(() ->
                resourceService.deleteResource(ResponseIdUtil.getBackgroundJobDescriptor(dialId), EtagHeader.ANY));
    }

    private BackgroundJobRecord loadJobRecord(String dialId) {
        String json = resourceService.getResource(ResponseIdUtil.getBackgroundJobDescriptor(dialId));
        return ProxyUtil.convertToObject(json, BackgroundJobRecord.class);
    }

    private Poller jobPoller(String dialId) {
        BackgroundJobRecord record = loadJobRecord(dialId);
        if (record == null) {
            return null;
        }
        return new Poller(dialId, record);
    }

    @VisibleForTesting
    Future<ResponsesApiClient.TerminalResult> poll(String dialId, String apiKey) {
        String url = settings.getLocalBaseUrl() + "/openai/v1/responses/" + dialId;
        return httpClient.request(new RequestOptions().setAbsoluteURI(url).setMethod(HttpMethod.GET))
                .compose(request -> request.putHeader(Proxy.HEADER_API_KEY, apiKey).send())
                .compose(response -> {
                    int statusCode = response.statusCode();
                    if (statusCode != 200) {
                        return Future.failedFuture("Unexpected status " + statusCode + " from DIAL for background job " + dialId);
                    }
                    return response.body().map(ResponsesApiClient::parseTerminalBody);
                });
    }

    private Future<Void> processResult(
            String responseId, BackgroundJobRecord jobRecord, ResponsesApiClient.TerminalResult result) {
        Config config = configStore.get();
        Deployment deployment = config.selectDeployment(jobRecord.deploymentName());
        ResourceDescriptor descriptor = ResponseIdUtil.getBackgroundJobDescriptor(responseId);
        String perRequestKey = decryptKey(descriptor, jobRecord.perRequestKey());
        TokenUsage usage = result == null ? null : result.usage();
        boolean hasUsage = usage != null && !usage.isEmpty();

        apiKeyStore.getApiKeyData(perRequestKey, null)
                .recover(e -> Future.succeededFuture(null))
                .onSuccess(apiKeyData -> {
                    String traceId = apiKeyData != null ? apiKeyData.getTraceId() : null;
                    String spanId = apiKeyData != null ? apiKeyData.getSpanId() : null;

                    // rate limiting only applies to Models; any deployment may still self-report
                    // usage for the statistics.usage_per_model breakdown
                    Future<Void> limitFuture = Future.succeededFuture();
                    if (deployment instanceof Model && hasUsage) {
                        Buffer requestBody = Buffer.buffer(jobRecord.requestBody());
                        // null liveUsageNode: this poller never streams, result.body() is a single
                        // buffered document, so ModelCostCalculator parses it directly.
                        limitFuture = rateLimiter.increase(
                                deployment, jobRecord.initiatorBucket(), usage, requestBody, result.body(),
                                InterfaceType.OPENAI_RESPONSES, null)
                                .transform(limitResult -> {
                                    if (limitResult.failed()) {
                                        log.warn("Failed to increase limit", limitResult.cause());
                                    }
                                    return Future.<Void>succeededFuture();
                                });
                    }

                    Future<List<UsagePerModel>> statsFuture = limitFuture.compose(ignored -> {
                        if (!hasUsage || traceId == null || spanId == null) {
                            return Future.succeededFuture(List.of());
                        }
                        return tokenStatsTracker.updateDeploymentStats(traceId, spanId, jobRecord.deploymentName(), usage)
                                .compose(stats -> recordAggregatedCosts(stats.aggregatedCosts(), responseMapping.getInitiatorBucket())
                                        .map(ignored2 -> stats.usagePerModel()));
                    });

                    if (traceId != null && Boolean.TRUE.equals(jobRecord.isRootSpan())) {
                        statsFuture = statsFuture.compose(list -> tokenStatsTracker.endSpan(traceId).map(ignored -> list));
                    }

                    Future<List<UsagePerModel>> finalStatsFuture = statsFuture;
                    statsFuture.eventually(() -> invalidatePerRequestKey(perRequestKey))
                            .onComplete(ignored -> {
                                List<UsagePerModel> usagePerModel = finalStatsFuture.succeeded()
                                        ? finalStatsFuture.result() : List.of();
                                logStore.save(AnalyticsLogContext.from(jobRecord, result, usagePerModel));
                            })
                            .onFailure(error -> log.error("Failed to finalize background job {}", responseId, error));
                });
        return Future.succeededFuture();
    }

    /**
     * Persists each ancestor's aggregated-cost increment collected by this report, mirroring
     * {@code BaseDeploymentPostController.recordAggregatedCosts} for the polled/background path, which
     * has no live {@link com.epam.aidial.core.server.ProxyContext} to derive a bucket from. Never fails
     * the caller: a write failure here is logged and swallowed.
     */
    private Future<Void> recordAggregatedCosts(List<TokenStatsTracker.AggregatedCost> aggregatedCosts, String bucket) {
        if (aggregatedCosts.isEmpty()) {
            return Future.succeededFuture();
        }
        List<Future<Void>> futures = new ArrayList<>(aggregatedCosts.size());
        for (TokenStatsTracker.AggregatedCost cost : aggregatedCosts) {
            futures.add(rateLimiter.recordAggregatedCost(cost.deploymentName(), bucket, cost.cost()));
        }
        return Future.all(futures).<Void>transform(result -> {
            if (result.failed()) {
                log.warn("Failed to record aggregated cost", result.cause());
            }
            return Future.succeededFuture();
        });
    }

    private String encryptKey(ResourceDescriptor descriptor, String key) {
        BucketInfo bucketInfo = new BucketInfo(descriptor.getBucketName(), descriptor.getBucketLocation());
        byte[] aad = descriptor.getAbsoluteFilePath().getBytes(StandardCharsets.UTF_8);
        byte[] cipher = encryptionService.encrypt(bucketInfo, key.getBytes(StandardCharsets.UTF_8), aad);
        return Base64.getEncoder().encodeToString(cipher);
    }

    private String decryptKey(ResourceDescriptor descriptor, String key) {
        BucketInfo bucketInfo = new BucketInfo(descriptor.getBucketName(), descriptor.getBucketLocation());
        byte[] aad = descriptor.getAbsoluteFilePath().getBytes(StandardCharsets.UTF_8);
        byte[] raw = Base64.getDecoder().decode(key);
        return new String(encryptionService.decrypt(bucketInfo, raw, aad), StandardCharsets.UTF_8);
    }

    private Future<Void> completeAndProcess(
            String dialId, BackgroundJobRecord record, ResponsesApiClient.TerminalResult result) {
        return taskExecutor.submit(() ->
                        resourceService.deleteResource(ResponseIdUtil.getBackgroundJobDescriptor(dialId), EtagHeader.ANY))
                .compose(deleted -> {
                    if (deleted) {
                        return processResult(dialId, record, result);
                    }
                    log.info("Background job {} already completed by another handler, skipping processing", dialId);
                    return Future.succeededFuture();
                });
    }

    private void expireJob(String dialId) {
        BackgroundJobRecord record = loadJobRecord(dialId);
        if (record == null) {
            return;
        }
        boolean deleted = resourceService.deleteResource(ResponseIdUtil.getBackgroundJobDescriptor(dialId), EtagHeader.ANY);
        if (deleted) {
            processResult(dialId, record, null)
                    .onFailure(e -> log.warn("BackgroundJobService: failed to finalize expired job {}", dialId, e));
        }
    }

    private Future<Boolean> invalidatePerRequestKey(String key) {
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey(key);
        return apiKeyStore.invalidatePerRequestApiKey(apiKeyData);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class Settings {
        String localBaseUrl = "http://localhost:8080";
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

    @RequiredArgsConstructor
    public class Poller {
        private final String dialId;
        private final BackgroundJobRecord record;

        public Future<Boolean> poll() {
            String apiKey = decryptKey(ResponseIdUtil.getBackgroundJobDescriptor(dialId), record.perRequestKey());
            return BackgroundJobService.this.poll(dialId, apiKey)
                    .compose(result -> {
                        if (result != null) {
                            return completeAndProcess(dialId, record, result).map(true);
                        }
                        return Future.succeededFuture(false);
                    });
        }

        public Future<Void> fail() {
            return completeAndProcess(dialId, record, null);
        }
    }
}
