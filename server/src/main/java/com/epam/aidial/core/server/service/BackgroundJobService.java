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
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.data.NodeType;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
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

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.annotation.Nullable;

@Slf4j
public class BackgroundJobService {

    static final long POLL_INTERVAL_MS = 5_000L;
    static final long LEASE_TTL_MS = 30_000L;
    static final int MAX_POLL_FAILURES = 10;
    public static final long DEFAULT_JOB_TTL_MS = 24L * 60 * 60 * 1000;
    static final long DEFAULT_CHECK_PERIOD_MS = 24L * 60 * 60 * 1000;
    static final long MAX_START_OFFSET_MS = 60_000L;
    private static final int PAGE_SIZE = 1000;

    private static final String TERMINAL_COMPLETED = "completed";
    private static final String TERMINAL_FAILED = "failed";
    private static final String TERMINAL_CANCELLED = "cancelled";
    private static final String TERMINAL_INCOMPLETE = "incomplete";

    private final ResourceService resourceService;
    private final ApiKeyStore apiKeyStore;
    private final TokenStatsTracker tokenStatsTracker;
    private final RateLimiter rateLimiter;
    private final ConfigStore configStore;
    private final UpstreamRouteProvider upstreamRouteProvider;
    private final HttpClient httpClient;
    private final HttpClientOptions clientOptions;
    private final AsyncTaskExecutor taskExecutor;
    private final LockService lockService;
    private final String instanceId;
    private Vertx vertx;

    public BackgroundJobService(ResourceService resourceService, ApiKeyStore apiKeyStore,
                                TokenStatsTracker tokenStatsTracker, RateLimiter rateLimiter,
                                ConfigStore configStore,
                                UpstreamRouteProvider upstreamRouteProvider, HttpClient httpClient,
                                HttpClientOptions clientOptions, AsyncTaskExecutor taskExecutor,
                                LockService lockService, Supplier<String> generator) {
        this.resourceService = resourceService;
        this.apiKeyStore = apiKeyStore;
        this.tokenStatsTracker = tokenStatsTracker;
        this.rateLimiter = rateLimiter;
        this.configStore = configStore;
        this.upstreamRouteProvider = upstreamRouteProvider;
        this.httpClient = httpClient;
        this.clientOptions = clientOptions;
        this.taskExecutor = taskExecutor;
        this.lockService = lockService;
        this.instanceId = generator.get();
    }

    public void init(Vertx vertx) {
        this.vertx = vertx;
        long offset = ThreadLocalRandom.current().nextLong(MAX_START_OFFSET_MS + 1);
        vertx.setTimer(offset, ignored -> {
            taskExecutor.submit(this::resumeActiveJobs);
            vertx.setPeriodic(DEFAULT_CHECK_PERIOD_MS, ignored2 -> taskExecutor.submit(this::cleanExpiredJobs));
        });
    }

    /**
     * Saves a background job record and schedules polling.
     * Must be called from a blocking context (taskExecutor).
     */
    public void saveJob(ProxyContext context, String dialResponseId, ResponseMapping mapping) {
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
        persistRecord(job);
        if (context.isStreamingRequest()) {
            // The initial stream delivers the full result; finalization is handled at stream end.
            context.setBackgroundJobId(jobId);
        } else {
            vertx.runOnContext(ignored -> startPolling(job));
        }
        log.info("Background job {} saved for deployment {}", jobId, mapping.getDeploymentName());
    }

    // ── Polling ────────────────────────────────────────────────────────────────

    private void startPolling(Job job) {
        scheduleNextPoll(job, new AtomicInteger());
    }

    private void scheduleNextPoll(Job job, AtomicInteger failureCount) {
        vertx.setTimer(POLL_INTERVAL_MS, ignored -> pollOnce(job, failureCount));
    }

    private void pollOnce(Job job, AtomicInteger failureCount) {
        if (isExpired(job.record())) {
            log.warn("Background job {} exceeded TTL, cleaning up", job.id());
            onJobExpired(job, () -> {});
            return;
        }

        tryClaimOrRenewAsync(job.id())
                .onSuccess(remaining -> {
                    if (remaining > 0) {
                        vertx.setTimer(remaining, ignored -> pollOnce(job, failureCount));
                        return;
                    }
                    taskExecutor.submit(() -> loadRecord(job.id()))
                            .onSuccess(freshJob -> {
                                if (freshJob == null) {
                                    releaseLeaseAsync(job.id());
                                    return;
                                }
                                long renewalId = vertx.setPeriodic(LEASE_TTL_MS / 2, id ->
                                        tryClaimOrRenewAsync(freshJob.id())
                                                .onFailure(e -> log.warn("Lease renewal failed for background job {}", freshJob.id(), e)));
                                Runnable cancelRenewal = () -> vertx.cancelTimer(renewalId);
                                doPoll(freshJob)
                                        .onSuccess(status -> {
                                            if (isTerminal(status.status())) {
                                                onJobCompleted(freshJob, status, cancelRenewal);
                                            } else {
                                                cancelRenewal.run();
                                                failureCount.set(0);
                                                scheduleNextPoll(freshJob, failureCount);
                                            }
                                        })
                                        .onFailure(error -> {
                                            int n = failureCount.incrementAndGet();
                                            if (n >= MAX_POLL_FAILURES) {
                                                log.error("Background job {} exceeded max poll failures, giving up", freshJob.id(), error);
                                                onJobExpired(freshJob, cancelRenewal);
                                            } else {
                                                cancelRenewal.run();
                                                log.warn("Poll failed for background job {} ({}/{})", freshJob.id(), n, MAX_POLL_FAILURES, error);
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
    private void onJobCompleted(Job job, JobStatus status, Runnable cancelRenewal) {
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
                .compose(ignored -> taskExecutor.submit(() -> {
                    deleteRecord(job.id());
                    cancelRenewal.run();
                    return null;
                }))
                .compose(ignored -> releaseLeaseAsync(job.id()))
                .onSuccess(ignored ->
                        log.info("Background job {} completed with status {}", job.id(), status.status()))
                .onFailure(error ->
                        log.error("Failed to finalize background job {}", job.id(), error));
    }

    /** Called on the event loop when the job has exceeded its TTL or poll failure limit. */
    private void onJobExpired(Job job, Runnable cancelRenewal) {
        invalidatePerRequestKey(job.record())
                .compose(ignored -> taskExecutor.submit(() -> {
                    deleteRecord(job.id());
                    cancelRenewal.run();
                    return null;
                }))
                .compose(ignored -> releaseLeaseAsync(job.id()))
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
        taskExecutor.submit(() -> {
                    deleteRecord(jobId);
                    return null;
                })
                .onSuccess(ignored ->
                        log.info("Streaming background job {} finalized", jobId))
                .onFailure(error ->
                        log.error("Failed to finalize streaming background job {}", jobId, error));
    }

    // ── Restart recovery ───────────────────────────────────────────────────────

    /** Scans blob storage for active background jobs and resumes polling for each. */
    private Void resumeActiveJobs() {
        log.info("Scanning for active background jobs to resume");
        try {
            ResourceDescriptor root = ResourceDescriptorFactory.fromDecoded(
                    ResourceTypes.BACKGROUND_JOB,
                    ResponseIdUtil.BACKGROUND_JOB_BUCKET,
                    ResponseIdUtil.BACKGROUND_JOB_BUCKET_LOCATION,
                    null);
            int resumed = resumeJobsUnder(root);
            log.info("Resumed {} background jobs", resumed);
        } catch (Throwable e) {
            log.warn("Failed to scan for active background jobs", e);
        }
        return null;
    }

    private int resumeJobsUnder(ResourceDescriptor root) {
        int count = 0;
        String token = null;
        do {
            ResourceFolderMetadata folder = resourceService.getFolderMetadata(root, token, PAGE_SIZE, false);
            if (folder == null) {
                break;
            }
            List<? extends MetadataBase> items = folder.getItems();
            if (items != null) {
                for (MetadataBase item : items) {
                    if (item.getNodeType() == NodeType.ITEM) {
                        try {
                            Job job = loadRecord(item.getName());
                            if (job != null) {
                                if (isExpired(job.record())) {
                                    log.warn("Background job {} already expired on startup, cleaning up", job.id());
                                    vertx.runOnContext(ignored -> onJobExpired(job, () -> {}));
                                } else if (job.record().isStreaming()) {
                                    reconnectSseStream(job);
                                    count++;
                                } else {
                                    vertx.runOnContext(ignored -> startPolling(job));
                                    count++;
                                }
                            }
                        } catch (Throwable e) {
                            log.warn("Failed to resume background job {}", item.getName(), e);
                        }
                    }
                }
            }
            token = folder.getNextToken();
        } while (token != null);
        return count;
    }

    void reconnectSseStream(Job job) {
        vertx.runOnContext(ignored -> doSseReconnect(job));
    }

    private void doSseReconnect(Job job) {
        if (isExpired(job.record())) {
            log.warn("Background job {} exceeded TTL on SSE reconnect, cleaning up", job.id());
            onJobExpired(job, () -> {});
            return;
        }

        tryClaimOrRenewAsync(job.id())
                .onSuccess(remaining -> {
                    if (remaining > 0) {
                        log.info("Background job {} lease held by another instance, skipping SSE reconnect", job.id());
                        return;
                    }
                    taskExecutor.submit(() -> loadRecord(job.id()))
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
                    onJobCompleted(job, status, cancelRenewal);
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
                onJobCompleted(job, jobStatus, cancelRenewal);
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

    private Void cleanExpiredJobs() {
        log.debug("Housekeeping: scanning for expired background jobs");
        try {
            ResourceDescriptor root = ResourceDescriptorFactory.fromDecoded(
                    ResourceTypes.BACKGROUND_JOB,
                    ResponseIdUtil.BACKGROUND_JOB_BUCKET,
                    ResponseIdUtil.BACKGROUND_JOB_BUCKET_LOCATION,
                    null);
            cleanExpiredJobsUnder(root);
        } catch (Throwable e) {
            log.warn("Housekeeping: failed to clean expired background jobs", e);
        }
        return null;
    }

    private void cleanExpiredJobsUnder(ResourceDescriptor root) {
        long now = System.currentTimeMillis();
        String token = null;
        do {
            ResourceFolderMetadata folder = resourceService.getFolderMetadata(root, token, PAGE_SIZE, false);
            if (folder == null) {
                break;
            }
            List<? extends MetadataBase> items = folder.getItems();
            if (items != null) {
                for (MetadataBase item : items) {
                    if (item.getNodeType() == NodeType.ITEM) {
                        Long createdAt = item instanceof com.epam.aidial.core.storage.data.ResourceItemMetadata meta
                                ? (meta.getCreatedAt() != null ? meta.getCreatedAt() : meta.getUpdatedAt())
                                : null;
                        if (createdAt != null && createdAt + DEFAULT_JOB_TTL_MS < now) {
                            try {
                                ResourceDescriptor descriptor = ResponseIdUtil.getBackgroundJobDescriptor(item.getName());
                                resourceService.deleteResource(descriptor, EtagHeader.ANY);
                                log.debug("Housekeeping: deleted expired background job {}", item.getName());
                            } catch (Throwable e) {
                                log.warn("Housekeeping: failed to delete expired background job {}", item.getName(), e);
                            }
                        }
                    }
                }
            }
            token = folder.getNextToken();
        } while (token != null);
    }

    // ── Storage helpers ────────────────────────────────────────────────────────

    private void persistRecord(Job job) {
        ResourceDescriptor descriptor = ResponseIdUtil.getBackgroundJobDescriptor(job.id());
        resourceService.putResource(descriptor, ProxyUtil.convertToString(job.record()), EtagHeader.NEW_ONLY);
    }

    private void deleteRecord(String jobId) {
        ResourceDescriptor descriptor = ResponseIdUtil.getBackgroundJobDescriptor(jobId);
        resourceService.deleteResource(descriptor, EtagHeader.ANY);
    }

    @Nullable
    private Job loadRecord(String jobId) {
        ResourceDescriptor descriptor = ResponseIdUtil.getBackgroundJobDescriptor(jobId);
        String json = resourceService.getResource(descriptor);
        BackgroundJobRecord record = ProxyUtil.convertToObject(json, BackgroundJobRecord.class);
        return record != null ? new Job(jobId, record) : null;
    }

    // ── Lease helpers ──────────────────────────────────────────────────────────

    private Future<Long> tryClaimOrRenewAsync(String jobId) {
        RFuture<Long> rf = lockService.tryClaimOrRenewAsync("background_job_poll:" + jobId, instanceId, LEASE_TTL_MS);
        return Future.fromCompletionStage(rf.toCompletableFuture(), vertx.getOrCreateContext())
                .map(result -> result != null ? result : LEASE_TTL_MS);
    }

    private Future<Void> releaseLeaseAsync(String jobId) {
        RFuture<Boolean> rf = lockService.releaseClaimAsync("background_job_poll:" + jobId, instanceId);
        return Future.fromCompletionStage(rf.toCompletableFuture(), vertx.getOrCreateContext()).mapEmpty();
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
