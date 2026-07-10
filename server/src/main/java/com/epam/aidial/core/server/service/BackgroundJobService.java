package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.encryption.CredentialEncryptionService;
import com.epam.aidial.core.server.ProxyContext;
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
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.google.common.annotations.VisibleForTesting;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@Slf4j
public class BackgroundJobService {
    private final ResponseMappingService responseMappingService;
    private final ResourceService resourceService;
    private final AsyncTaskExecutor taskExecutor;
    private final ConfigStore configStore;
    private final ApiKeyStore apiKeyStore;
    private final RateLimiter rateLimiter;
    private final TokenStatsTracker tokenStatsTracker;
    private final UpstreamRouteProvider upstreamRouteProvider;
    private final ResponsesApiClient client;
    private final LogStore logStore;
    private final CredentialEncryptionService encryptionService;

    public BackgroundJobService(
            ResponseMappingService responseMappingService,
            ResourceService resourceService,
            AsyncTaskExecutor taskExecutor,
            ConfigStore configStore,
            ApiKeyStore apiKeyStore,
            RateLimiter rateLimiter,
            TokenStatsTracker tokenStatsTracker,
            UpstreamRouteProvider upstreamRouteProvider,
            ResponsesApiClient client,
            LogStore logStore,
            CredentialEncryptionService encryptionService) {
        this.responseMappingService = responseMappingService;
        this.resourceService = resourceService;
        this.taskExecutor = taskExecutor;
        this.configStore = configStore;
        this.apiKeyStore = apiKeyStore;
        this.rateLimiter = rateLimiter;
        this.tokenStatsTracker = tokenStatsTracker;
        this.upstreamRouteProvider = upstreamRouteProvider;
        this.client = client;
        this.logStore = logStore;
        this.encryptionService = encryptionService;
    }

    public Future<Void> persistJobRecord(ProxyContext context) {
        String dialId = context.getDialResponseId();
        ResourceDescriptor descriptor = ResponseIdUtil.getBackgroundJobDescriptor(dialId);
        BackgroundJobRecord record = BackgroundJobRecord.from(context, key -> encryptKey(descriptor, key));
        String json = ProxyUtil.convertToString(record);
        return taskExecutor.submit(() -> resourceService.putResource(descriptor, json, EtagHeader.NEW_ONLY)).mapEmpty();
    }

    public Future<Boolean> isJobActive(String dialId) {
        return taskExecutor.submit(() ->
                resourceService.hasResource(ResponseIdUtil.getBackgroundJobDescriptor(dialId)));
    }

    public Future<Boolean> deleteJobRecord(String dialId) {
        return taskExecutor.submit(() ->
                resourceService.deleteResource(ResponseIdUtil.getBackgroundJobDescriptor(dialId), EtagHeader.ANY));
    }

    public Future<Boolean> tryCompleteOnGet(String dialId, ResponseMapping mapping, ResponsesApiClient.TerminalResult result) {
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
                        return Future.succeededFuture(false);
                    }
                    return processResult(dialId, record, mapping, result)
                            .map(true);
                });
    }

    public Poller jobPoller(String dialId) {
        Map.Entry<BackgroundJobRecord, ResponseMapping> pair = loadJobData(dialId);
        if (pair == null) {
            return null;
        }
        return new Poller(dialId, pair.getKey(), pair.getValue());
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

    public Future<Void> processResult(
            String responseId, BackgroundJobRecord jobRecord, ResponseMapping responseMapping, ResponsesApiClient.TerminalResult result) {
        Config config = configStore.get();
        Deployment deployment = config.selectDeployment(responseMapping.getDeploymentName());
        ResourceDescriptor descriptor = ResponseIdUtil.getBackgroundJobDescriptor(responseId);
        String perRequestKey = decryptKey(descriptor, jobRecord.perRequestKey());

        apiKeyStore.getApiKeyData(perRequestKey, null)
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
                    future.eventually(() -> invalidatePerRequestKey(perRequestKey))
                            .onComplete(ignored -> logStore.save(AnalyticsLogContext.from(jobRecord, result)))
                            .onFailure(error -> log.error("Failed to finalize background job {}", responseId, error));
                });
        return Future.succeededFuture();
    }

    private BackgroundJobRecord loadBackgroundJobRecord(String dialId) {
        String json = resourceService.getResource(ResponseIdUtil.getBackgroundJobDescriptor(dialId));
        return ProxyUtil.convertToObject(json, BackgroundJobRecord.class);
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
            String dialId, BackgroundJobRecord record, ResponseMapping mapping, ResponsesApiClient.TerminalResult result) {
        return taskExecutor.submit(() ->
                        resourceService.deleteResource(ResponseIdUtil.getBackgroundJobDescriptor(dialId), EtagHeader.ANY))
                .compose(deleted -> {
                    if (deleted) {
                        return processResult(dialId, record, mapping, result);
                    }
                    log.info("Background job {} already completed by another handler, skipping processing", dialId);
                    return Future.succeededFuture();
                });
    }

    public void expireJob(String dialId) {
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

    @RequiredArgsConstructor
    public class Poller {
        private final String dialId;
        private final BackgroundJobRecord record;
        private final ResponseMapping mapping;

        public Future<Boolean> poll() {
            return pollMapping(mapping)
                    .compose(result -> {
                                if (result != null) {
                                    return completeAndProcess(dialId, record, mapping, result)
                                            .map(true);
                                }

                                return Future.succeededFuture(false);
                            }
                    );
        }

        public Future<Void> fail() {
            return completeAndProcess(dialId, record, mapping, null);
        }
    }
}
