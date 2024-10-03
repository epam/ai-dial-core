package com.epam.aidial.core.service;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.controller.ApplicationUtil;
import com.epam.aidial.core.data.ListSharedResourcesRequest;
import com.epam.aidial.core.data.MetadataBase;
import com.epam.aidial.core.data.NodeType;
import com.epam.aidial.core.data.ResourceFolderMetadata;
import com.epam.aidial.core.data.ResourceItemMetadata;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.data.SharedResourcesResponse;
import com.epam.aidial.core.security.AccessService;
import com.epam.aidial.core.security.EncryptionService;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.EtagHeader;
import com.epam.aidial.core.util.HttpException;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import com.epam.aidial.core.util.UrlUtil;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.epam.aidial.core.storage.BlobStorageUtil.PATH_SEPARATOR;

@Slf4j
public class ApplicationService {

    private static final String DEPLOYMENTS_NAME = "deployments";
    private static final int PAGE_SIZE = 1000;

    private final Vertx vertx;
    private final HttpClient httpClient;
    private final EncryptionService encryptionService;
    private final ResourceService resourceService;
    private final LockService lockService;
    private final Supplier<String> idGenerator;
    private final RScoredSortedSet<String> pendingApplications;
    private final String controllerEndpoint;
    private final long controllerTimeout;
    private final long checkDelay;
    private final int checkSize;
    @Getter
    private final boolean includeCustomApps;

    public ApplicationService(Vertx vertx,
                              HttpClient httpClient,
                              RedissonClient redis,
                              EncryptionService encryptionService,
                              ResourceService resourceService,
                              LockService lockService,
                              Supplier<String> idGenerator,
                              JsonObject settings) {
        String pendingApplicationsKey = BlobStorageUtil.toStoragePath(lockService.getPrefix(), "pending-applications");

        this.vertx = vertx;
        this.httpClient = httpClient;
        this.encryptionService = encryptionService;
        this.resourceService = resourceService;
        this.lockService = lockService;
        this.idGenerator = idGenerator;
        this.pendingApplications = redis.getScoredSortedSet(pendingApplicationsKey, StringCodec.INSTANCE);
        this.controllerEndpoint = settings.getString("controllerEndpoint", "https://ai-dial-app-controller.deltixuat.com");
        this.controllerTimeout = settings.getLong("controllerTimeout", 120000L);
        this.checkDelay = settings.getLong("checkDelay", 300000L);
        this.checkSize = settings.getInteger("checkSize", 64);
        this.includeCustomApps = settings.getBoolean("includeCustomApps", false);

        long checkPeriod = settings.getLong("checkPeriod", 300000L);
        vertx.setPeriodic(checkPeriod, checkPeriod, ignore -> vertx.executeBlocking(this::checkApplications));
    }

    public static boolean hasDeploymentAccess(ProxyContext context, ResourceDescription resource) {
        if (resource.getBucketLocation().contains(DEPLOYMENTS_NAME)) {
            String location = BlobStorageUtil.buildInitiatorBucket(context);
            String reviewLocation = location + DEPLOYMENTS_NAME + PATH_SEPARATOR;
            return resource.getBucketLocation().startsWith(reviewLocation);
        }

        return false;
    }

    public List<Application> getAllApplications(ProxyContext context) {
        List<Application> applications = new ArrayList<>();
        applications.addAll(getPrivateApplications(context));
        applications.addAll(getSharedApplications(context));
        applications.addAll(getPublicApplications(context));
        return applications;
    }

    public List<Application> getPrivateApplications(ProxyContext context) {
        String location = BlobStorageUtil.buildInitiatorBucket(context);
        String bucket = encryptionService.encrypt(location);

        ResourceDescription folder = ResourceDescription.fromDecoded(ResourceType.APPLICATION, bucket, location, null);
        return getApplications(folder);
    }

    public List<Application> getSharedApplications(ProxyContext context) {
        String location = BlobStorageUtil.buildInitiatorBucket(context);
        String bucket = encryptionService.encrypt(location);

        ListSharedResourcesRequest request = new ListSharedResourcesRequest();
        request.setResourceTypes(Set.of(ResourceType.APPLICATION));

        ShareService shares = context.getProxy().getShareService();
        SharedResourcesResponse response = shares.listSharedWithMe(bucket, location, request);
        Set<MetadataBase> metadata = response.getResources();

        List<Application> list = new ArrayList<>();

        for (MetadataBase meta : metadata) {
            ResourceDescription resource = ResourceDescription.fromAnyUrl(meta.getUrl(), encryptionService);

            if (meta instanceof ResourceItemMetadata) {
                list.add(getApplication(resource).getValue());
            } else {
                list.addAll(getApplications(resource));
            }
        }

        return list;
    }

    public List<Application> getPublicApplications(ProxyContext context) {
        ResourceDescription folder = ResourceDescription.fromDecoded(ResourceType.APPLICATION, BlobStorageUtil.PUBLIC_BUCKET, BlobStorageUtil.PUBLIC_LOCATION, null);
        AccessService accessService = context.getProxy().getAccessService();
        return getApplications(folder, page -> accessService.filterForbidden(context, folder, page));
    }

    public Pair<ResourceItemMetadata, Application> getApplication(ResourceDescription resource) {
        verifyApplication(resource);
        Pair<ResourceItemMetadata, String> result = resourceService.getResourceWithMetadata(resource);

        if (result == null) {
            throw new ResourceNotFoundException("Application is not found: " + resource.getUrl());
        }

        ResourceItemMetadata meta = result.getKey();
        Application application = ProxyUtil.convertToObject(result.getValue(), Application.class, true);

        if (application == null) {
            throw new ResourceNotFoundException("Application is not found: " + resource.getUrl());
        }

        return Pair.of(meta, application);
    }

    public List<Application> getApplications(ResourceDescription resource) {
        Consumer<ResourceFolderMetadata> noop = ignore -> {
        };
        return getApplications(resource, noop);
    }

    public List<Application> getApplications(ResourceDescription resource, Consumer<ResourceFolderMetadata> filter) {
        if (!resource.isFolder() || resource.getType() != ResourceType.APPLICATION) {
            throw new IllegalArgumentException("Invalid application folder: " + resource.getUrl());
        }

        List<Application> applications = new ArrayList<>();
        String nextToken = null;

        do {
            ResourceFolderMetadata folder = resourceService.getFolderMetadata(resource, nextToken, PAGE_SIZE, true);
            if (folder == null) {
                break;
            }

            filter.accept(folder);

            for (MetadataBase meta : folder.getItems()) {
                if (meta.getNodeType() == NodeType.ITEM && meta.getResourceType() == ResourceType.APPLICATION) {
                    try {
                        ResourceDescription item = ResourceDescription.fromAnyUrl(meta.getUrl(), encryptionService);
                        Application application = getApplication(item).getValue();
                        applications.add(application);
                    } catch (ResourceNotFoundException ignore) {
                        // deleted while fetching
                    }
                }
            }

            nextToken = folder.getNextToken();
        } while (nextToken != null);

        return applications;
    }

    public Pair<ResourceItemMetadata, Application> putApplication(ResourceDescription resource, EtagHeader etag, Application application) {
        prepareApplication(resource, etag, application);

        ResourceItemMetadata meta = resourceService.computeResource(resource, etag, json -> {
            Application existing = ProxyUtil.convertToObject(json, Application.class, true);
            Application.Function function = application.getFunction();

            if (function == null && isActive(existing)) {
                throw new HttpException(HttpStatus.CONFLICT, "The application must be stopped");
            }

            if (function != null) {
                if (existing == null || existing.getFunction() == null) {
                    function.setId(UrlUtil.encodePathSegment(idGenerator.get()));
                    function.setStatus(Application.Function.Status.CREATED);
                    function.setTargetFolder(encodeTargetFolder(resource, function.getId()));
                } else {
                    application.setEndpoint(existing.getEndpoint());
                    application.getFeatures().setRateEndpoint(existing.getFeatures().getRateEndpoint());
                    application.getFeatures().setTokenizeEndpoint(existing.getFeatures().getTokenizeEndpoint());
                    application.getFeatures().setTruncatePromptEndpoint(existing.getFeatures().getTruncatePromptEndpoint());
                    application.getFeatures().setConfigurationEndpoint(existing.getFeatures().getConfigurationEndpoint());
                    function.setId(existing.getFunction().getId());
                    function.setStatus(existing.getFunction().getStatus());
                    function.setTargetFolder(existing.getFunction().getTargetFolder());
                }
            }

            return ProxyUtil.convertToString(application, true);
        });

        return Pair.of(meta, application);
    }

    public void deleteApplication(ResourceDescription resource, EtagHeader etag) {
        verifyApplication(resource);

        resourceService.computeResource(resource, etag, json -> {
            Application application = ProxyUtil.convertToObject(json, Application.class, true);

            if (application == null) {
                throw new ResourceNotFoundException("Application is not found: " + resource.getUrl());
            }

            if (isActive(application)) {
                throw new HttpException(HttpStatus.NOT_FOUND, "Application must be stopped: " + resource.getUrl());
            }

            return null;
        });
    }

    public void copyApplication(ResourceDescription source, ResourceDescription destination, boolean overwrite) {
        copyApplication(source, destination, overwrite, application -> {
            // nothing
        });
    }

    public void copyApplication(ResourceDescription source, ResourceDescription destination, boolean overwrite, Consumer<Application> function) {
        verifyApplication(source);
        verifyApplication(destination);

        Application application = getApplication(source).getValue();
        EtagHeader etag = overwrite ? EtagHeader.ANY : EtagHeader.NEW_ONLY;

        prepareApplication(destination, EtagHeader.ANY, application);
        resourceService.computeResource(destination, etag, json -> {
            Application existing = ProxyUtil.convertToObject(json, Application.class, true);

            if (isActive(existing)) {
                throw new HttpException(HttpStatus.CONFLICT, "The application must be stopped: " + destination.getUrl());
            }

            return ProxyUtil.convertToString(application, true);
        });
    }

    public Application startApplication(ProxyContext context, ResourceDescription resource) {
        verifyApplication(resource);
        verifyController();

        AtomicReference<Application> result = new AtomicReference<>();
        resourceService.computeResource(resource, json -> {
            Application application = ProxyUtil.convertToObject(json, Application.class, true);
            if (application == null) {
                throw new ResourceNotFoundException("Application is not found: " + resource.getUrl());
            }

            if (application.getFunction() == null) {
                throw new HttpException(HttpStatus.CONFLICT, "Application does not have function: " + resource.getUrl());
            }

            if (isActive(application)) {
                throw new HttpException(HttpStatus.CONFLICT, "Application must be stopped: " + resource.getUrl());
            }

            application.getFunction().setStatus(Application.Function.Status.STARTING);
            result.setPlain(application);

            pendingApplications.add(System.currentTimeMillis() + checkDelay, resource.getUrl());
            return ProxyUtil.convertToString(application, true);
        });

        vertx.executeBlocking(() -> launchApplication(context, resource), false)/*
                .onFailure(error -> vertx.executeBlocking(() -> terminateApplication(resource), false))*/;

        return result.getPlain();
    }

    public Application stopApplication(ResourceDescription resource) {
        verifyApplication(resource);
        verifyController();

        AtomicReference<Application> result = new AtomicReference<>();
        resourceService.computeResource(resource, json -> {
            Application application = ProxyUtil.convertToObject(json, Application.class, true);
            if (application == null) {
                throw new ResourceNotFoundException("Application is not found: " + resource.getUrl());
            }

            if (application.getFunction() == null) {
                throw new HttpException(HttpStatus.CONFLICT, "Application does not have function: " + resource.getUrl());
            }

            if (application.getFunction().getStatus() != Application.Function.Status.STARTED) {
                throw new HttpException(HttpStatus.CONFLICT, "Application is not started: " + resource.getUrl());
            }

            // add to queue

            application.setEndpoint(null);
            application.getFunction().setStatus(Application.Function.Status.STOPPING);

            result.setPlain(application);
            pendingApplications.add(System.currentTimeMillis() + checkDelay, resource.getUrl());

            return ProxyUtil.convertToString(application, true);
        });

        vertx.executeBlocking(() -> terminateApplication(resource), false);
        return result.getPlain();
    }

    private void prepareApplication(ResourceDescription resource, EtagHeader etag,
                                    Application application) {
        verifyApplication(resource);

        if (application.getEndpoint() == null && application.getFunction() == null) {
            throw new IllegalArgumentException("Application endpoint or function must be provided");
        }

        application.setName(resource.getUrl());
        application.setUserRoles(Set.of());
        application.setForwardAuthToken(false);

        // reject request if both If-None-Match header and reference provided
        if (application.getReference() != null && !etag.isOverwrite()) {
            throw new IllegalArgumentException("Creating application with provided reference is not allowed");
        }

        if (application.getReference() == null) {
            application.setReference(ApplicationUtil.generateReference());
        }

        Application.Function function = application.getFunction();
        if (function != null) {
            if (application.getFeatures() == null) {
                application.setFeatures(new Features());
            }

            application.setEndpoint(null);
            application.getFeatures().setRateEndpoint(null);
            application.getFeatures().setTokenizeEndpoint(null);
            application.getFeatures().setTruncatePromptEndpoint(null);
            application.getFeatures().setConfigurationEndpoint(null);

            if (function.getSourceFolder() == null) {
                throw new IllegalArgumentException("Application function source folder must be provided");
            }

            try {
                ResourceDescription folder = ResourceDescription.fromAnyUrl(function.getSourceFolder(), encryptionService);

                if (!folder.isFolder() || folder.getType() != ResourceType.FILE || !folder.getBucketName().equals(resource.getBucketName())) {
                    throw new IllegalArgumentException();
                }

                function.setSourceFolder(folder.getUrl());
            } catch (Throwable e) {
                throw new IllegalArgumentException("Application function sources must be a valid file folder: " + function.getSourceFolder());
            }
        }
    }

    private Void checkApplications() {
        log.debug("Checking pending applications");
        try {
            long now = System.currentTimeMillis();

            for (String redisKey : pendingApplications.valueRange(Double.NEGATIVE_INFINITY, true, now, true, 0, checkSize)) {
                log.debug("Checking pending application: {}", redisKey);
                ResourceDescription resource = ResourceDescription.fromAnyUrl(redisKey, encryptionService);

                try {
                    terminateApplication(resource);
                } catch (Throwable e) {
                    // ignore
                }
            }
        } catch (Throwable e) {
            log.warn("Failed to check pending applications:", e);
        }

        return null;
    }

    // region Launching Application

    private Void launchApplication(ProxyContext context, ResourceDescription resource) {
        try (LockService.Lock lock = lockService.tryLock(deploymentLockKey(resource))) {
            if (lock == null) {
                throw new IllegalStateException("Application function is locked: " + resource.getUrl());
            }

            Application application = getApplication(resource).getValue();
            Application.Function function = application.getFunction();

            if (function == null) {
                throw new IllegalStateException("Application has no function: " + resource.getUrl());
            }

            if (function.getStatus() != Application.Function.Status.STARTING) {
                throw new IllegalStateException("Application is not starting: " + resource.getUrl());
            }

            copyApplicationSources(function);
            createApplicationImage(context, function);
            String endpoint = createApplicationDeployment(context, function);

            resourceService.computeResource(resource, json -> {
                Application existing = ProxyUtil.convertToObject(json, Application.class, true);
                if (existing == null || !Objects.equals(existing.getFunction(), application.getFunction())) {
                    throw new IllegalStateException("Application function has been updated");
                }

                function.setStatus(Application.Function.Status.STARTED);
                existing.setEndpoint(endpoint);
                existing.setFunction(function);
                return ProxyUtil.convertToString(existing, true);
            });

            pendingApplications.remove(resource.getUrl());
            return null;
        } catch (Throwable error) {
            log.warn("Failed to launch application: {}", resource.getUrl(), error);
            throw error;
        }
    }

    private void copyApplicationSources(Application.Function function) {
        String sourceFolderUrl = function.getSourceFolder();
        String targetFolderUrl = function.getTargetFolder();
        ResourceDescription sourceFolder = ResourceDescription.fromAnyUrl(sourceFolderUrl, encryptionService);

        String token = null;
        do {
            ResourceFolderMetadata folder = resourceService.getFolderMetadata(sourceFolder, token, PAGE_SIZE, true);
            if (folder == null) {
                throw new IllegalStateException("Application function source folder is empty");
            }

            for (MetadataBase item : folder.getItems()) {
                String sourceFileUrl = item.getUrl();
                String targetFileUrl = targetFolderUrl + sourceFileUrl.substring(sourceFolder.getUrl().length());

                ResourceDescription sourceFile = ResourceDescription.fromAnyUrl(sourceFileUrl, encryptionService);
                ResourceDescription targetFile = ResourceDescription.fromAnyUrl(targetFileUrl, encryptionService);

                if (!resourceService.copyResource(sourceFile, targetFile)) {
                    throw new IllegalStateException("Can't copy function source file: " + sourceFileUrl);
                }
            }

            token = folder.getNextToken();
        } while (token != null);
    }

    private void createApplicationImage(ProxyContext context, Application.Function function) {
        callController(HttpMethod.POST, "/v1/image/create",
                request -> {
                    String apiKey = context.getRequest().getHeader(Proxy.HEADER_API_KEY);
                    String auth = context.getRequest().getHeader(HttpHeaders.AUTHORIZATION);

                    if (apiKey != null) {
                        request.putHeader(Proxy.HEADER_API_KEY, apiKey);
                    }

                    if (auth != null) {
                        request.putHeader(HttpHeaders.AUTHORIZATION, auth);
                    }

                    request.putHeader(HttpHeaders.CONTENT_TYPE, Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON);

                    CreateImageRequest body = new CreateImageRequest(function.getId(), function.getTargetFolder());
                    return ProxyUtil.convertToString(body);
                },
                (response, body) -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("Failed to create image. Status: " + response.statusCode());
                    }

                    return ProxyUtil.convertToObject(body, CreateImageResponse.class);
                });
    }

    private String createApplicationDeployment(ProxyContext context, Application.Function function) {
        CreateDeploymentResponse deployment = callController(HttpMethod.POST, "/v1/service/create",
                request -> {
                    String apiKey = context.getRequest().getHeader(Proxy.HEADER_API_KEY);
                    String auth = context.getRequest().getHeader(HttpHeaders.AUTHORIZATION);

                    if (apiKey != null) {
                        request.putHeader(Proxy.HEADER_API_KEY, apiKey);
                    }

                    if (auth != null) {
                        request.putHeader(HttpHeaders.AUTHORIZATION, auth);
                    }

                    request.putHeader(HttpHeaders.CONTENT_TYPE, Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON);

                    CreateDeploymentRequest body = new CreateDeploymentRequest(function.getId());
                    return ProxyUtil.convertToString(body);
                },
                (response, body) -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("Failed to create deployment. Status: " + response.statusCode());
                    }

                    return ProxyUtil.convertToObject(body, CreateDeploymentResponse.class);
                });

        return deployment.url();
    }

    // endregion

    // region Terminate Application

    private Void terminateApplication(ResourceDescription resource) {
        try (LockService.Lock lock = lockService.tryLock(deploymentLockKey(resource))) {
            if (lock == null) {
                return null;
            }

            Application application;

            try {
                application = getApplication(resource).getValue();
            } catch (ResourceNotFoundException e) {
                application = null;
            }

            if (isPending(application)) {
                Application.Function function = application.getFunction();
                removeApplicationTargets(function);
                deleteApplicationImage(function);
                deleteApplicationDeployment(function);

                resourceService.computeResource(resource, json -> {
                    Application existing = ProxyUtil.convertToObject(json, Application.class, true);
                    if (existing == null || !Objects.equals(existing.getFunction(), function)) {
                        throw new IllegalStateException("Application function has been updated");
                    }

                    Application.Function.Status status = (function.getStatus() == Application.Function.Status.STOPPING)
                            ? Application.Function.Status.STOPPED
                            : Application.Function.Status.FAILED;

                    function.setStatus(status);
                    existing.setFunction(function);
                    return ProxyUtil.convertToString(existing, true);
                });
            }

            pendingApplications.remove(resource.getUrl());
            return null;
        } catch (Throwable e) {
            log.warn("Failed to terminate application: {}", resource.getUrl(), e);
            throw e;
        }
    }

    private void removeApplicationTargets(Application.Function function) {
        ResourceDescription folder = ResourceDescription.fromAnyUrl(function.getTargetFolder(), encryptionService);

        String token = null;
        do {
            ResourceFolderMetadata metadata = resourceService.getFolderMetadata(folder, token, 1000, true);
            if (metadata == null) {
                break;
            }

            for (MetadataBase item : metadata.getItems()) {
                ResourceDescription file = ResourceDescription.fromAnyUrl(item.getUrl(), encryptionService);

                if (!resourceService.deleteResource(file, EtagHeader.ANY)) {
                    throw new IllegalStateException("Can't delete function target file: " + item.getUrl());
                }
            }

            token = metadata.getNextToken();
        } while (token != null);
    }

    private void deleteApplicationImage(Application.Function function) {
        callController(HttpMethod.DELETE, "/v1/image/delete/" + function.getId(),
                request -> null,
                (response, body) -> {
                    if (response.statusCode() != 200 && response.statusCode() != 404) {
                        throw new RuntimeException("Failed to delete image. Status: " + response.statusCode());
                    }

                    return null;
                });
    }

    private void deleteApplicationDeployment(Application.Function function) {
        callController(HttpMethod.DELETE, "/v1/service/delete/" + function.getId(),
                request -> null,
                (response, body) -> {
                    if (response.statusCode() != 200 && response.statusCode() != 404) {
                        throw new RuntimeException("Failed to delete deployment. Status: " + response.statusCode());
                    }

                    return null;
                });
    }

    // endregion

    private <R> R callController(HttpMethod method, String path,
                                 Function<HttpClientRequest, String> requestMapper,
                                 BiFunction<HttpClientResponse, String, R> responseMapper) {
        CompletableFuture<R> resultFuture = new CompletableFuture<>();
        AtomicReference<HttpClientRequest> requestReference = new AtomicReference<>();
        AtomicReference<HttpClientResponse> responseReference = new AtomicReference<>();

        RequestOptions requestOptions = new RequestOptions()
                .setMethod(method)
                .setAbsoluteURI(controllerEndpoint + path)
                .setIdleTimeout(controllerTimeout);

        httpClient.request(requestOptions)
                .compose(request -> {
                    requestReference.set(request);
                    String body = requestMapper.apply(request);
                    return request.send((body == null) ? "" : body);
                })
                .compose(response -> {
                    responseReference.set(response);
                    return response.body();
                })
                .map(buffer -> {
                    HttpClientResponse response = responseReference.get();
                    String body = buffer.toString(StandardCharsets.UTF_8);
                    return responseMapper.apply(response, body);
                })
                .onSuccess(resultFuture::complete)
                .onFailure(resultFuture::completeExceptionally);

        try {
            return resultFuture.get(controllerTimeout, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            HttpClientRequest request = requestReference.get();

            if (request != null) {
                request.reset();
            }

            throw new RuntimeException(e);
        }
    }

    private String deploymentLockKey(ResourceDescription resource) {
        return BlobStorageUtil.toStoragePath(lockService.getPrefix(), "deployment:" + resource.getAbsoluteFilePath());
    }

    private String encodeTargetFolder(ResourceDescription resource, String id) {
        String location = resource.getBucketLocation()
                          + DEPLOYMENTS_NAME + PATH_SEPARATOR
                          + id + PATH_SEPARATOR;

        String name = encryptionService.encrypt(location);
        return ResourceDescription.fromDecoded(ResourceType.FILE, name, location, null).getUrl();
    }

    private static boolean isActive(Application application) {
        return application != null && application.getFunction() != null && isActive(application.getFunction().getStatus());
    }

    private static boolean isActive(Application.Function.Status status) {
        return switch (status) {
            case CREATED, FAILED, STOPPED -> false;
            case STARTING, STARTED, STOPPING -> true;
        };
    }

    private static boolean isPending(Application application) {
        return application != null && application.getFunction() != null && isPending(application.getFunction().getStatus());
    }

    private static boolean isPending(Application.Function.Status status) {
        return switch (status) {
            case CREATED, STARTED, FAILED, STOPPED -> false;
            case STARTING, STOPPING -> true;
        };
    }

    private static void verifyApplication(ResourceDescription resource) {
        if (resource.isFolder() || resource.getType() != ResourceType.APPLICATION) {
            throw new IllegalArgumentException("Invalid application url: " + resource.getUrl());
        }
    }

    private void verifyController() {
        if (controllerEndpoint == null) {
            throw new HttpException(HttpStatus.SERVICE_UNAVAILABLE, "The functionality is not available");
        }
    }

    private record CreateImageRequest(String name, String sources) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateImageResponse() {
    }

    public record CreateDeploymentRequest(String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateDeploymentResponse(String url) {
    }
}