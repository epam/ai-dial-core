package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.BackgroundJobRecord;
import com.epam.aidial.core.server.data.ResponseMapping;
import com.epam.aidial.core.server.limiter.RateLimiter;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.sse.SseEvent;
import com.epam.aidial.core.server.sse.SseEventListener;
import com.epam.aidial.core.server.sse.SseParser;
import com.epam.aidial.core.server.token.TokenStatsTracker;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.upstream.UpstreamRouteProvider;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.util.ResponseIdUtil;
import com.epam.aidial.core.storage.blobstore.BlobStorageUtil;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.util.RedisUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RFuture;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Slf4j
public class BackgroundJobService {

    static final long POLL_INTERVAL_MS = 5_000L;
    static final long LEASE_TTL_MS = 30_000L;
    static final int MAX_SEQUENTIAL_POLL_FAILURES = 10;
    public static final long DEFAULT_JOB_TTL_MS = 24L * 60 * 60 * 1000;
    static final long DEFAULT_CHECK_PERIOD_MS = 24L * 60 * 60 * 1000;

    private static final String TERMINAL_COMPLETED = "completed";
    private static final String TERMINAL_FAILED = "failed";
    private static final String TERMINAL_CANCELLED = "cancelled";
    private static final String TERMINAL_INCOMPLETE = "incomplete";

    private final RedissonClient redis;
    private final String prefix;
    private final String jobIndexKey;
    private final ApiKeyStore apiKeyStore;
    private final TokenStatsTracker tokenStatsTracker;
    private final RateLimiter rateLimiter;
    private final ConfigStore configStore;
    private final UpstreamRouteProvider upstreamRouteProvider;
    private final HttpClient httpClient;
    private final HttpClientOptions clientOptions;
    private final LockService lockService;
    private final String instanceId;
    private Vertx vertx;

    public BackgroundJobService(RedissonClient redis, String prefix,
                                ApiKeyStore apiKeyStore,
                                TokenStatsTracker tokenStatsTracker, RateLimiter rateLimiter,
                                ConfigStore configStore,
                                UpstreamRouteProvider upstreamRouteProvider, HttpClient httpClient,
                                HttpClientOptions clientOptions,
                                LockService lockService, Supplier<String> generator) {
        this.redis = redis;
        this.prefix = prefix;
        this.jobIndexKey = "background_job_index:" + BlobStorageUtil.toStoragePath(prefix, ResponseIdUtil.BACKGROUND_JOB_BUCKET_LOCATION);
        this.apiKeyStore = apiKeyStore;
        this.tokenStatsTracker = tokenStatsTracker;
        this.rateLimiter = rateLimiter;
        this.configStore = configStore;
        this.upstreamRouteProvider = upstreamRouteProvider;
        this.httpClient = httpClient;
        this.clientOptions = clientOptions;
        this.lockService = lockService;
        this.instanceId = generator.get();
    }

    public void init(Vertx vertx) {
        this.vertx = vertx;
        resumeActiveJobs();
        vertx.setPeriodic(DEFAULT_CHECK_PERIOD_MS, ignored -> cleanStaleIndexEntries());
    }

    public Future<Void> saveJob(ProxyContext context, String dialResponseId, ResponseMapping mapping) {
        String jobId = context.getProxy().getGenerator().get();
        BackgroundJobRecord record = BackgroundJobRecord.builder()
                .dialResponseId(dialResponseId)
                .mapping(mapping)
                .perRequestKey(context.getProxyApiKeyData().getPerRequestKey())
                .traceId(context.getTraceId())
                .spanId(context.getSpanId())
                .createdAt(System.currentTimeMillis())
                .streaming(context.isStreamingRequest())
                .build();
        Job job = new Job(jobId, record);
        return persistRecordAsync(job)
                .onSuccess(ignored -> {
                    if (context.isStreamingRequest()) {
                        context.setBackgroundJobId(jobId);
                    } else {
                        startPolling(job);
                    }
                    log.info("Background job {} saved for deployment {}", jobId, mapping.getDeploymentName());
                });
    }

    // ── Polling ────────────────────────────────────────────────────────────────

    private void startPolling(Job job) {
        scheduleNextPoll(job, new AtomicInteger());
    }

    private void scheduleNextPoll(Job job, AtomicInteger failureCount) {
        vertx.setTimer(POLL_INTERVAL_MS, ignored -> pollOnce(job, failureCount));
    }

    private void pollOnce(Job job, AtomicInteger failureCount) {
        tryClaimOrRenewAsync(job.id())
                .onSuccess(remaining -> {
                    if (remaining > 0) {
                        vertx.setTimer(remaining, ignored -> pollOnce(job, failureCount));
                        return;
                    }
                    if (isExpired(job.record())) {
                        log.warn("Background job {} exceeded TTL, cleaning up", job.id());
                        deleteRecordAsync(job.id())
                                .eventually(() -> releaseLeaseAsync(job.id()))
                                .onSuccess(ignored -> onJobExpired(job));
                        return;
                    }
                    loadRecordAsync(job.id())
                            .onSuccess(freshJob -> {
                                if (freshJob == null) {
                                    releaseLeaseAsync(job.id());
                                    return;
                                }
                                long renewalId = vertx.setPeriodic(LEASE_TTL_MS / 2, id ->
                                        tryClaimOrRenewAsync(freshJob.id())
                                                .onFailure(e -> log.warn("Lease renewal failed for background job {}", freshJob.id(), e)));
                                doPoll(freshJob)
                                        .onSuccess(status -> {
                                            if (isTerminal(status.status())) {
                                                deleteRecordAsync(freshJob.id())
                                                        .eventually(() -> {
                                                            vertx.cancelTimer(renewalId);
                                                            return releaseLeaseAsync(job.id());
                                                        })
                                                        .onSuccess(ignored -> onJobCompleted(freshJob, status));
                                            } else {
                                                vertx.cancelTimer(renewalId);
                                                failureCount.set(0);
                                                scheduleNextPoll(freshJob, failureCount);
                                            }
                                        })
                                        .onFailure(error -> {
                                            int n = failureCount.incrementAndGet();
                                            if (n >= MAX_SEQUENTIAL_POLL_FAILURES) {
                                                log.error("Background job {} exceeded max poll failures, giving up", freshJob.id(), error);
                                                deleteRecordAsync(freshJob.id())
                                                        .eventually(() -> {
                                                            vertx.cancelTimer(renewalId);
                                                            return releaseLeaseAsync(job.id());
                                                        })
                                                        .onSuccess(ignored -> onJobExpired(freshJob));
                                            } else {
                                                vertx.cancelTimer(renewalId);
                                                log.warn("Poll failed for background job {} ({}/{})", freshJob.id(), n, MAX_SEQUENTIAL_POLL_FAILURES, error);
                                                scheduleNextPoll(freshJob, failureCount);
                                            }
                                        });
                            })
                            .onFailure(error -> {
                                log.warn("Failed to load record for background job {}", job.id(), error);
                                scheduleNextPoll(job, failureCount);
                            });
                })
                .onFailure(error -> {
                    log.warn("Lease check failed for background job {}", job.id(), error);
                    scheduleNextPoll(job, failureCount);
                });
    }

    /** Makes an HTTP GET to the upstream's responses endpoint and parses status + usage. */
    private Future<JobStatus> doPoll(Job job) {
        ResponseMapping mapping = job.record().getMapping();
        if (mapping == null) {
            return Future.failedFuture("Mapping is missing for background job " + job.id());
        }

        Config config = configStore.get();
        Deployment deployment = config.selectDeployment(mapping.getDeploymentName());
        if (deployment == null || deployment.getResponsesEndpoint() == null) {
            return Future.failedFuture("Deployment not found or has no responses endpoint for job " + job.id());
        }

        UpstreamRoute upstreamRoute;
        try {
            upstreamRoute = upstreamRouteProvider.get(deployment, null, mapping.getUpstreamKey());
        } catch (Exception e) {
            return Future.failedFuture("Cannot create upstream route for job " + job.id() + ": " + e.getMessage());
        }

        Upstream upstream;
        try {
            upstream = upstreamRoute.next();
        } catch (Exception e) {
            return Future.failedFuture("No upstream available for job " + job.id() + ": " + e.getMessage());
        }

        String targetUrl = deployment.getResponsesEndpoint() + "/" + mapping.getUpstreamResponseId();
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(targetUrl)
                .setMethod(HttpMethod.GET)
                .setConnectTimeout(clientOptions.getConnectTimeout())
                .setIdleTimeout(clientOptions.getIdleTimeout());

        return httpClient.request(options)
                .compose(request -> {
                    request.putHeader(Proxy.HEADER_UPSTREAM_KEY, upstream.getKey());
                    if (upstream.getResponsesEndpoint() != null) {
                        request.putHeader(Proxy.HEADER_UPSTREAM_ENDPOINT, upstream.getResponsesEndpoint());
                    }
                    if (upstream.getExtraData() != null) {
                        request.putHeader(Proxy.HEADER_UPSTREAM_EXTRA_DATA, upstream.getExtraData());
                    }
                    return request.send();
                })
                .compose(response -> response.body().map(body -> {
                    try {
                        ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(body.getBytes());
                        String status = tree.path("status").asText(null);
                        TokenUsage tokenUsage = null;
                        JsonNode usageNode = tree.get("usage");
                        if (usageNode != null && !usageNode.isNull()) {
                            tokenUsage = ProxyUtil.MAPPER.treeToValue(usageNode, TokenUsage.class);
                        }
                        return new JobStatus(status, tokenUsage, response.statusCode());
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to parse poll response for job " + job.id(), e);
                    }
                }));
    }

    // ── Completion ─────────────────────────────────────────────────────────────

    /** Called on the event loop when a terminal status is observed. Chains async cleanup. */
    private void onJobCompleted(Job job, JobStatus status) {
        BackgroundJobRecord record = job.record();
        ResponseMapping mapping = record.getMapping();
        Config config = configStore.get();
        Deployment deployment = mapping != null ? config.selectDeployment(mapping.getDeploymentName()) : null;

        Future<Void> tokenFuture = Future.succeededFuture();
        if (status.tokenUsage() != null && record.getTraceId() != null && record.getSpanId() != null) {
            tokenFuture = tokenStatsTracker.updateStats(record.getTraceId(), record.getSpanId(), status.tokenUsage())
                    .compose(ignored -> tokenStatsTracker.endRootSpan(record.getTraceId()));
        }

        Future<Void> rateLimitFuture = Future.succeededFuture();
        if (deployment instanceof Model && status.tokenUsage() != null) {
            String bucketLocation = mapping.getInitiatorBucket();
            rateLimitFuture = rateLimiter.increase(deployment, bucketLocation, status.tokenUsage(), null, null)
                    .recover(err -> {
                        log.warn("Failed to update rate limit for background job {}", job.id(), err);
                        return Future.succeededFuture();
                    });
        }

        Future.all(tokenFuture, rateLimitFuture)
                .compose(ignored -> invalidatePerRequestKey(record))
                .onSuccess(ignored ->
                        log.info("Background job {} completed with status {}", job.id(), status.status()))
                .onFailure(error ->
                        log.error("Failed to finalize background job {}", job.id(), error));
    }

    /** Called on the event loop when the job has exceeded its TTL or poll failure limit. */
    private void onJobExpired(Job job) {
        invalidatePerRequestKey(job.record())
                .onSuccess(ignored ->
                        log.warn("Background job {} expired and was cleaned up", job.id()))
                .onFailure(error ->
                        log.error("Failed to clean up expired background job {}", job.id(), error));
    }

    /**
     * Finalizes a streaming background job after the SSE stream has been fully consumed.
     * Rate limits and span cleanup are handled by the normal {@code finalizeRequest} flow;
     * this only deletes the job record (fire-and-forget).
     */
    public void finalizeStreamingJob(ProxyContext context) {
        String jobId = context.getBackgroundJobId();
        if (jobId == null) {
            return;
        }
        deleteRecordAsync(jobId)
                .onSuccess(ignored ->
                        log.info("Streaming background job {} finalized", jobId))
                .onFailure(error ->
                        log.error("Failed to finalize streaming background job {}", jobId, error));
    }

    // ── Restart recovery ───────────────────────────────────────────────────────

    /** Reads the job index from Redis and resumes polling for each active job. */
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
                                    if (isExpired(job.record())) {
                                        log.warn("Background job {} already expired on startup, cleaning up", job.id());
                                        deleteRecordAsync(job.id()).onSuccess(ignored -> onJobExpired(job));
                                    } else if (job.record().isStreaming()) {
                                        reconnectSseStream(job);
                                        log.info("Resumed streaming background job {}", jobId);
                                    } else {
                                        startPolling(job);
                                        log.info("Resumed polling background job {}", jobId);
                                    }
                                })
                                .onFailure(e -> log.warn("Failed to resume background job {}", jobId, e));
                    }
                })
                .onFailure(e -> log.warn("Failed to scan for active background jobs", e));
    }

    void reconnectSseStream(Job job) {
        vertx.runOnContext(ignored -> doSseReconnect(job));
    }

    private void doSseReconnect(Job job) {
        tryClaimOrRenewAsync(job.id())
                .onSuccess(remaining -> {
                    if (remaining > 0) {
                        log.info("Background job {} lease held by another instance, skipping SSE reconnect", job.id());
                        return;
                    }
                    if (isExpired(job.record())) {
                        log.warn("Background job {} exceeded TTL on SSE reconnect, cleaning up", job.id());
                        deleteRecordAsync(job.id())
                                .eventually(() -> releaseLeaseAsync(job.id()))
                                .onSuccess(ignored -> onJobExpired(job));
                        return;
                    }
                    loadRecordAsync(job.id())
                            .onSuccess(freshJob -> {
                                if (freshJob == null) {
                                    releaseLeaseAsync(job.id());
                                    return;
                                }
                                long renewalId = vertx.setPeriodic(LEASE_TTL_MS / 2, id ->
                                        tryClaimOrRenewAsync(freshJob.id())
                                                .onFailure(e -> log.warn("Lease renewal failed for background job {}", freshJob.id(), e)));
                                Runnable cancelRenewal = () -> vertx.cancelTimer(renewalId);
                                attemptSseConnect(freshJob, cancelRenewal);
                            })
                            .onFailure(error -> {
                                log.warn("Failed to load record for background job {} on SSE reconnect", job.id(), error);
                                startPolling(job);
                            });
                })
                .onFailure(error -> {
                    log.warn("Lease check failed for background job {} on SSE reconnect", job.id(), error);
                    startPolling(job);
                });
    }

    private void attemptSseConnect(Job job, Runnable cancelRenewal) {
        ResponseMapping mapping = job.record().getMapping();
        Config config = configStore.get();
        Deployment deployment = mapping != null ? config.selectDeployment(mapping.getDeploymentName()) : null;
        if (mapping == null || deployment == null || deployment.getResponsesEndpoint() == null) {
            log.warn("Cannot reconnect SSE for job {} - missing mapping/deployment, falling back to polling", job.id());
            cancelRenewal.run();
            releaseLeaseAsync(job.id());
            startPolling(job);
            return;
        }

        UpstreamRoute upstreamRoute;
        Upstream upstream;
        try {
            upstreamRoute = upstreamRouteProvider.get(deployment, null, mapping.getUpstreamKey());
            upstream = upstreamRoute.next();
        } catch (Exception e) {
            log.warn("Cannot create upstream route for job {}, falling back to polling", job.id(), e);
            cancelRenewal.run();
            releaseLeaseAsync(job.id());
            startPolling(job);
            return;
        }

        String targetUrl = deployment.getResponsesEndpoint() + "/" + mapping.getUpstreamResponseId() + "?stream=true";
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(targetUrl)
                .setMethod(HttpMethod.GET)
                .setConnectTimeout(clientOptions.getConnectTimeout())
                .setIdleTimeout(clientOptions.getIdleTimeout());

        httpClient.request(options)
                .compose(request -> {
                    request.putHeader(Proxy.HEADER_UPSTREAM_KEY, upstream.getKey());
                    if (upstream.getResponsesEndpoint() != null) {
                        request.putHeader(Proxy.HEADER_UPSTREAM_ENDPOINT, upstream.getResponsesEndpoint());
                    }
                    if (upstream.getExtraData() != null) {
                        request.putHeader(Proxy.HEADER_UPSTREAM_EXTRA_DATA, upstream.getExtraData());
                    }
                    return request.send();
                })
                .onSuccess(response -> {
                    if (response.statusCode() != 200) {
                        log.warn("SSE reconnect got HTTP {} for job {}, falling back to polling", response.statusCode(), job.id());
                        cancelRenewal.run();
                        releaseLeaseAsync(job.id());
                        startPolling(job);
                        return;
                    }
                    String contentType = response.getHeader(HttpHeaders.CONTENT_TYPE);
                    if (contentType != null
                            && contentType.toLowerCase(Locale.ROOT).contains(Proxy.HEADER_CONTENT_TYPE_TEXT_EVENT_STREAM)) {
                        consumeSseStream(job, response, cancelRenewal);
                    } else {
                        response.body()
                                .onSuccess(body -> finishReconnectFromJsonBody(job, body, cancelRenewal))
                                .onFailure(error -> {
                                    log.warn("Failed to read JSON response for job {}, falling back to polling", job.id(), error);
                                    cancelRenewal.run();
                                    releaseLeaseAsync(job.id());
                                    startPolling(job);
                                });
                    }
                })
                .onFailure(error -> {
                    log.warn("SSE reconnect request failed for job {}, falling back to polling", job.id(), error);
                    cancelRenewal.run();
                    releaseLeaseAsync(job.id());
                    startPolling(job);
                });
    }

    private void consumeSseStream(Job job, HttpClientResponse response, Runnable cancelRenewal) {
        AtomicReference<JobStatus> terminalStatusRef = new AtomicReference<>();
        SseParser parser = new SseParser(512, new SseEventListener() {
            @Override
            public void onEvent(SseEvent event) {
                if (terminalStatusRef.get() != null) {
                    return;
                }
                String eventType = event.getEvent();
                if ("response.completed".equals(eventType) || "response.incomplete".equals(eventType)
                        || "response.failed".equals(eventType) || "response.cancelled".equals(eventType)) {
                    try {
                        JsonNode tree = ProxyUtil.MAPPER.readTree(event.getData());
                        String status = tree.path("status").asText(null);
                        TokenUsage tokenUsage = null;
                        JsonNode usageNode = tree.get("usage");
                        if (usageNode != null && !usageNode.isNull()) {
                            tokenUsage = ProxyUtil.MAPPER.treeToValue(usageNode, TokenUsage.class);
                        }
                        if (status != null) {
                            terminalStatusRef.set(new JobStatus(status, tokenUsage, 200));
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse terminal SSE event for job {}", job.id(), e);
                    }
                }
            }

            @Override
            public void onComment(String comment) {}

            @Override
            public void onComplete() {
                JobStatus status = terminalStatusRef.get();
                if (status != null && isTerminal(status.status())) {
                    deleteRecordAsync(job.id())
                            .eventually(() -> {
                                cancelRenewal.run();
                                return releaseLeaseAsync(job.id());
                            })
                            .onSuccess(ignored -> onJobCompleted(job, status));
                } else {
                    log.warn("SSE reconnect stream ended without terminal status for job {}, falling back to polling", job.id());
                    cancelRenewal.run();
                    releaseLeaseAsync(job.id());
                    startPolling(job);
                }
            }
        });

        response.handler(chunk -> parser.parse(chunk));
        response.endHandler(v -> parser.finish());
        response.exceptionHandler(error -> {
            log.warn("SSE reconnect stream error for job {}, falling back to polling", job.id(), error);
            cancelRenewal.run();
            releaseLeaseAsync(job.id());
            startPolling(job);
        });
    }

    private void finishReconnectFromJsonBody(Job job, Buffer body, Runnable cancelRenewal) {
        try {
            ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(body.getBytes());
            String status = tree.path("status").asText(null);
            TokenUsage tokenUsage = null;
            JsonNode usageNode = tree.get("usage");
            if (usageNode != null && !usageNode.isNull()) {
                tokenUsage = ProxyUtil.MAPPER.treeToValue(usageNode, TokenUsage.class);
            }
            JobStatus jobStatus = new JobStatus(status, tokenUsage, 200);
            if (isTerminal(status)) {
                deleteRecordAsync(job.id())
                        .eventually(() -> {
                            cancelRenewal.run();
                            return releaseLeaseAsync(job.id());
                        })
                        .onSuccess(ignored -> onJobCompleted(job, jobStatus));
            } else {
                cancelRenewal.run();
                releaseLeaseAsync(job.id());
                startPolling(job);
            }
        } catch (Exception e) {
            log.warn("Failed to parse JSON response for reconnect job {}, falling back to polling", job.id(), e);
            cancelRenewal.run();
            releaseLeaseAsync(job.id());
            startPolling(job);
        }
    }

    // ── Cleanup ────────────────────────────────────────────────────────────────

    /** Removes index entries whose Redis buckets have already expired. */
    private void cleanStaleIndexEntries() {
        log.debug("Housekeeping: scanning for stale background job index entries");
        toFuture(redis.<String>getSet(jobIndexKey, StringCodec.INSTANCE).readAllAsync())
                .onSuccess(jobIds -> {
                    for (String jobId : jobIds) {
                        loadRecordAsync(jobId)
                                .onSuccess(job -> {
                                    if (job == null) {
                                        redis.<String>getSet(jobIndexKey, StringCodec.INSTANCE).removeAsync(jobId);
                                        log.debug("Housekeeping: removed stale index entry for background job {}", jobId);
                                    }
                                })
                                .onFailure(e -> log.warn("Housekeeping: failed to check background job {}", jobId, e));
                    }
                })
                .onFailure(e -> log.warn("Housekeeping: failed to clean stale background job index entries", e));
    }

    // ── Storage helpers ────────────────────────────────────────────────────────

    private Future<Void> persistRecordAsync(Job job) {
        String json = ProxyUtil.convertToString(job.record());
        Future<Void> setFuture = toFuture(
                redis.<String>getBucket(toRedisKey(job.id()), StringCodec.INSTANCE)
                        .setAsync(json, DEFAULT_JOB_TTL_MS, TimeUnit.MILLISECONDS))
                .mapEmpty();
        Future<Boolean> addFuture = toFuture(redis.<String>getSet(jobIndexKey, StringCodec.INSTANCE)
                .addAsync(job.id()));
        return Future.all(setFuture, addFuture).mapEmpty();
    }

    private Future<Void> deleteRecordAsync(String jobId) {
        Future<Boolean> deleteFuture = toFuture(redis.<String>getBucket(toRedisKey(jobId), StringCodec.INSTANCE).deleteAsync());
        Future<Boolean> removeFuture = toFuture(redis.<String>getSet(jobIndexKey, StringCodec.INSTANCE).removeAsync(jobId));
        return Future.all(deleteFuture, removeFuture).mapEmpty();
    }

    /** Async load for event-loop callers; returns a succeeded future with {@code null} when the record is absent. */
    private Future<Job> loadRecordAsync(String jobId) {
        return toFuture(redis.<String>getBucket(toRedisKey(jobId), StringCodec.INSTANCE).getAsync())
                .map(json -> {
                    BackgroundJobRecord record = ProxyUtil.convertToObject(json, BackgroundJobRecord.class);
                    return record != null ? new Job(jobId, record) : null;
                });
    }

    private String toRedisKey(String jobId) {
        ResourceDescriptor resource = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.BACKGROUND_JOB, ResponseIdUtil.BACKGROUND_JOB_BUCKET,
                ResponseIdUtil.BACKGROUND_JOB_BUCKET_LOCATION, jobId);
        return RedisUtil.redisKey(resource, prefix);
    }

    // ── Lease helpers ──────────────────────────────────────────────────────────

    private Future<Long> tryClaimOrRenewAsync(String jobId) {
        RFuture<Long> rf = lockService.tryClaimOrRenewAsync("background_job_poll:" + jobId, instanceId, LEASE_TTL_MS);
        return toFuture(rf).map(result -> result != null ? result : LEASE_TTL_MS);
    }

    private Future<Void> releaseLeaseAsync(String jobId) {
        RFuture<Boolean> rf = lockService.releaseClaimAsync("background_job_poll:" + jobId, instanceId);
        return toFuture(rf).mapEmpty();
    }

    private <T> Future<T> toFuture(RFuture<T> rf) {
        return Future.fromCompletionStage(rf.toCompletableFuture(), vertx.getOrCreateContext());
    }

    // ── Key helpers ────────────────────────────────────────────────────────────

    private Future<Boolean> invalidatePerRequestKey(BackgroundJobRecord record) {
        String key = record.getPerRequestKey();
        if (key == null) {
            return Future.succeededFuture(true);
        }
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey(key);
        return apiKeyStore.invalidatePerRequestApiKey(apiKeyData);
    }

    // ── Utilities ──────────────────────────────────────────────────────────────

    private static boolean isExpired(BackgroundJobRecord record) {
        return System.currentTimeMillis() > record.getCreatedAt() + DEFAULT_JOB_TTL_MS;
    }

    private static boolean isTerminal(String status) {
        return TERMINAL_COMPLETED.equals(status)
                || TERMINAL_FAILED.equals(status)
                || TERMINAL_CANCELLED.equals(status)
                || TERMINAL_INCOMPLETE.equals(status);
    }

    record Job(String id, BackgroundJobRecord record) {}

    private record JobStatus(String status, TokenUsage tokenUsage, int httpStatus) {}
}
