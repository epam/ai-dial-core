package com.epam.aidial.core.service;

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
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.json.JsonObject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.commons.lang3.tuple.Pair;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.epam.aidial.core.storage.BlobStorageUtil.PATH_SEPARATOR;

@Slf4j
public class ApplicationService {

    private static final String DEPLOYMENTS_NAME = "deployments";
    private static final int PAGE_SIZE = 1000;

    private final Vertx vertx;
    private final EncryptionService encryptionService;
    private final ResourceService resourceService;
    private final LockService lockService;
    private final Supplier<String> idGenerator;
    private final RScoredSortedSet<String> pendingApplications;
    private final ApplicationOperatorService controller;
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
        this.encryptionService = encryptionService;
        this.resourceService = resourceService;
        this.lockService = lockService;
        this.idGenerator = idGenerator;
        this.pendingApplications = redis.getScoredSortedSet(pendingApplicationsKey, StringCodec.INSTANCE);
        this.controller = new ApplicationOperatorService(httpClient, settings);
        this.checkDelay = settings.getLong("checkDelay", 300000L);
        this.checkSize = settings.getInteger("checkSize", 64);
        this.includeCustomApps = settings.getBoolean("includeCustomApps", false);

        if (controller.isActive()) {
            long checkPeriod = settings.getLong("checkPeriod", 300000L);
            vertx.setPeriodic(checkPeriod, checkPeriod, ignore -> vertx.executeBlocking(this::checkApplications));
        }
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
        Application application = ProxyUtil.convertToObject(result.getValue(), Application.class);

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
        prepareApplication(resource, application);

        ResourceItemMetadata meta = resourceService.computeResource(resource, etag, json -> {
            Application existing = ProxyUtil.convertToObject(json, Application.class);
            Application.Function function = application.getFunction();

            if (function != null) {
                if (existing == null || existing.getFunction() == null) {
                    if (isPublicOrReview(resource)) {
                        throw new HttpException(HttpStatus.CONFLICT, "The application function cannot be created in public/review bucket");
                    }

                    function.setId(UrlUtil.encodePathSegment(idGenerator.get()));
                    function.setAuthorBucket(resource.getBucketName());
                    function.setStatus(Application.Function.Status.CREATED);
                    function.setTargetFolder(encodeTargetFolder(resource, function.getId()));
                } else {
                    if (isPublicOrReview(resource) && !function.getSourceFolder().equals(existing.getFunction().getSourceFolder())) {
                        throw new HttpException(HttpStatus.CONFLICT, "The application function source folder cannot be updated in public/review bucket");
                    }

                    application.setEndpoint(existing.getEndpoint());
                    application.getFeatures().setRateEndpoint(existing.getFeatures().getRateEndpoint());
                    application.getFeatures().setTokenizeEndpoint(existing.getFeatures().getTokenizeEndpoint());
                    application.getFeatures().setTruncatePromptEndpoint(existing.getFeatures().getTruncatePromptEndpoint());
                    application.getFeatures().setConfigurationEndpoint(existing.getFeatures().getConfigurationEndpoint());
                    function.setId(existing.getFunction().getId());
                    function.setAuthorBucket(existing.getFunction().getAuthorBucket());
                    function.setStatus(existing.getFunction().getStatus());
                    function.setTargetFolder(existing.getFunction().getTargetFolder());
                    function.setError(existing.getFunction().getError());
                }
            }

            return ProxyUtil.convertToString(application);
        });

        return Pair.of(meta, application);
    }

    public void deleteApplication(ResourceDescription resource, EtagHeader etag) {
        verifyApplication(resource);
        MutableObject<Application> reference = new MutableObject<>();

        resourceService.computeResource(resource, etag, json -> {
            Application application = ProxyUtil.convertToObject(json, Application.class);

            if (application == null) {
                throw new ResourceNotFoundException("Application is not found: " + resource.getUrl());
            }

            if (isActive(application)) {
                throw new HttpException(HttpStatus.CONFLICT, "Application must be stopped: " + resource.getUrl());
            }

            reference.setValue(application);
            return null;
        });

        Application application = reference.getValue();

        if (isPublicOrReview(resource) && application.getFunction() != null) {
            resourceService.deleteFolder(application.getFunction().getSourceFolder());
        }
    }

    public void copyApplication(ResourceDescription source, ResourceDescription destination, boolean overwrite, Consumer<Application> consumer) {
        verifyApplication(source);
        verifyApplication(destination);

        Application application = getApplication(source).getValue();
        Application.Function function = application.getFunction();

        EtagHeader etag = overwrite ? EtagHeader.ANY : EtagHeader.NEW_ONLY;
        consumer.accept(application);
        application.setName(destination.getUrl());

        boolean isPublicOrReview = isPublicOrReview(destination);
        String sourceFolder = (function == null) ? null : function.getSourceFolder();

        resourceService.computeResource(destination, etag, json -> {
            Application existing = ProxyUtil.convertToObject(json, Application.class);

            if (function != null) {
                if (existing == null || existing.getFunction() == null) {
                    function.setId(UrlUtil.encodePathSegment(idGenerator.get()));
                    function.setStatus(Application.Function.Status.CREATED);
                    function.setTargetFolder(encodeTargetFolder(destination, function.getId()));

                    if (isPublicOrReview) {
                        function.setSourceFolder(function.getTargetFolder());
                    } else {
                        function.setAuthorBucket(destination.getBucketName());
                    }
                } else {
                    if (isPublicOrReview) {
                        throw new HttpException(HttpStatus.CONFLICT, "The application function must be deleted in public/review bucket");
                    }

                    application.setEndpoint(existing.getEndpoint());
                    application.getFeatures().setRateEndpoint(existing.getFeatures().getRateEndpoint());
                    application.getFeatures().setTokenizeEndpoint(existing.getFeatures().getTokenizeEndpoint());
                    application.getFeatures().setTruncatePromptEndpoint(existing.getFeatures().getTruncatePromptEndpoint());
                    application.getFeatures().setConfigurationEndpoint(existing.getFeatures().getConfigurationEndpoint());
                    function.setId(existing.getFunction().getId());
                    function.setAuthorBucket(existing.getFunction().getAuthorBucket());
                    function.setStatus(existing.getFunction().getStatus());
                    function.setTargetFolder(existing.getFunction().getTargetFolder());
                    function.setError(existing.getFunction().getError());
                }
            }

            return ProxyUtil.convertToString(application);
        });

        // for public/review application source folder is equal to target folder
        // source files are copied to read-only deployment bucket for such applications
        if (isPublicOrReview && function != null) {
            resourceService.copyFolder(sourceFolder, function.getSourceFolder(), false);
        }
    }

    public Application startApplication(ProxyContext context, ResourceDescription resource) {
        verifyApplication(resource);
        controller.verifyActive();

        MutableObject<Application> result = new MutableObject<>();
        resourceService.computeResource(resource, json -> {
            Application application = ProxyUtil.convertToObject(json, Application.class);
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
            application.getFunction().setError(null);

            result.setValue(application);
            pendingApplications.add(System.currentTimeMillis() + checkDelay, resource.getUrl());

            return ProxyUtil.convertToString(application);
        });

        vertx.executeBlocking(() -> launchApplication(context, resource), false)
                .onFailure(error -> vertx.executeBlocking(() -> terminateApplication(resource, error.getMessage()), false));

        return result.getValue();
    }

    public Application stopApplication(ResourceDescription resource) {
        verifyApplication(resource);
        controller.verifyActive();

        MutableObject<Application> result = new MutableObject<>();
        resourceService.computeResource(resource, json -> {
            Application application = ProxyUtil.convertToObject(json, Application.class);
            if (application == null) {
                throw new ResourceNotFoundException("Application is not found: " + resource.getUrl());
            }

            if (application.getFunction() == null) {
                throw new HttpException(HttpStatus.CONFLICT, "Application does not have function: " + resource.getUrl());
            }

            if (application.getFunction().getStatus() != Application.Function.Status.STARTED) {
                throw new HttpException(HttpStatus.CONFLICT, "Application is not started: " + resource.getUrl());
            }

            application.setEndpoint(null);
            application.getFeatures().setRateEndpoint(null);
            application.getFeatures().setTokenizeEndpoint(null);
            application.getFeatures().setTruncatePromptEndpoint(null);
            application.getFeatures().setConfigurationEndpoint(null);
            application.getFunction().setStatus(Application.Function.Status.STOPPING);

            result.setValue(application);
            pendingApplications.add(System.currentTimeMillis() + checkDelay, resource.getUrl());

            return ProxyUtil.convertToString(application);
        });

        vertx.executeBlocking(() -> terminateApplication(resource, null), false);
        return result.getValue();
    }

    public Application.Logs getApplicationLogs(ResourceDescription resource) {
        verifyApplication(resource);
        controller.verifyActive();

        Application application = getApplication(resource).getValue();

        if (application.getFunction() == null || application.getFunction().getStatus() != Application.Function.Status.STARTED) {
            throw new HttpException(HttpStatus.CONFLICT, "Application is not started: " + resource.getUrl());
        }

        return controller.getApplicationLogs(application.getFunction());
    }

    private void prepareApplication(ResourceDescription resource, Application application) {
        verifyApplication(resource);

        if (application.getEndpoint() == null && application.getFunction() == null) {
            throw new IllegalArgumentException("Application endpoint or function must be provided");
        }

        application.setName(resource.getUrl());
        application.setUserRoles(null);
        application.setForwardAuthToken(false);

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
            function.setAuthorBucket(resource.getBucketName());
            function.setError(null);

            if (function.getRuntime() == null) {
                throw new IllegalArgumentException("Application function runtime must be provided");
            }

            if (function.getEnv() == null) {
                function.setEnv(Map.of());
            }

            if (function.getMapping() == null) {
                throw new IllegalArgumentException("Application function mapping must be provided");
            }

            verifyMapping(function.getMapping().getCompletion(), true, "Application completion mapping is missing/invalid");
            verifyMapping(function.getMapping().getRate(), false, "Application rate mapping is invalid");
            verifyMapping(function.getMapping().getTokenize(), false, "Application tokenize mapping is invalid");
            verifyMapping(function.getMapping().getTruncatePrompt(), false, "Application truncate_prompt mapping is invalid");
            verifyMapping(function.getMapping().getConfiguration(), false, "Application configuration mapping is invalid");

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
                    terminateApplication(resource, "Application failed to start in the specified interval");
                } catch (Throwable e) {
                    // ignore
                }
            }
        } catch (Throwable e) {
            log.warn("Failed to check pending applications:", e);
        }

        return null;
    }

    private Void launchApplication(ProxyContext context, ResourceDescription resource) {
        // right now there is no lock watchdog mechanism
        // this lock can expire before this operation is finished
        // for extra safety the controller timeout is less than lock timeout
        try (LockService.Lock lock = lockService.tryLock(deploymentLockKey(resource))) {
            if (lock == null) {
                throw new IllegalStateException("Application function is locked");
            }

            Application application = getApplication(resource).getValue();
            Application.Function function = application.getFunction();

            if (function == null) {
                throw new IllegalStateException("Application has no function");
            }

            if (function.getStatus() != Application.Function.Status.STARTING) {
                throw new IllegalStateException("Application is not starting");
            }

            // for public/review application source folder is equal to target folder
            // source files are copied to read-only deployment bucket for such applications
            if (!isPublicOrReview(resource)) {
                resourceService.copyFolder(function.getSourceFolder(), function.getTargetFolder(), false);
            }

            controller.createApplicationImage(context, function);
            String endpoint = controller.createApplicationDeployment(context, function);

            resourceService.computeResource(resource, json -> {
                Application existing = ProxyUtil.convertToObject(json, Application.class);
                if (existing == null || !Objects.equals(existing.getFunction(), application.getFunction())) {
                    throw new IllegalStateException("Application function has been updated");
                }

                function.setStatus(Application.Function.Status.STARTED);
                existing.setFunction(function);
                existing.setEndpoint(buildMapping(endpoint, function.getMapping().getCompletion()));
                existing.getFeatures().setRateEndpoint(buildMapping(endpoint, function.getMapping().getRate()));
                existing.getFeatures().setTokenizeEndpoint(buildMapping(endpoint, function.getMapping().getTokenize()));
                existing.getFeatures().setTruncatePromptEndpoint(buildMapping(endpoint, function.getMapping().getTruncatePrompt()));
                existing.getFeatures().setConfigurationEndpoint(buildMapping(endpoint, function.getMapping().getConfiguration()));

                return ProxyUtil.convertToString(existing);
            });

            pendingApplications.remove(resource.getUrl());
            return null;
        } catch (Throwable error) {
            log.warn("Failed to launch application: {}", resource.getUrl(), error);
            throw error;
        }
    }

    private Void terminateApplication(ResourceDescription resource, String error) {
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

                // for public/review application source folder is equal to target folder
                // source files are copied to read-only deployment bucket for such applications
                if (!isPublicOrReview(resource)) {
                    resourceService.deleteFolder(function.getTargetFolder());
                }

                controller.deleteApplicationImage(function);
                controller.deleteApplicationDeployment(function);

                resourceService.computeResource(resource, json -> {
                    Application existing = ProxyUtil.convertToObject(json, Application.class);
                    if (existing == null || !Objects.equals(existing.getFunction(), function)) {
                        throw new IllegalStateException("Application function has been updated");
                    }

                    Application.Function.Status status = (function.getStatus() == Application.Function.Status.STOPPING)
                            ? Application.Function.Status.STOPPED
                            : Application.Function.Status.FAILED;

                    function.setStatus(status);
                    function.setError(status == Application.Function.Status.FAILED ? error : null);

                    existing.setFunction(function);
                    return ProxyUtil.convertToString(existing);
                });
            }

            pendingApplications.remove(resource.getUrl());
            return null;
        } catch (Throwable e) {
            log.warn("Failed to terminate application: {}", resource.getUrl(), e);
            throw e;
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

    public static boolean isActive(Application application) {
        return application != null && application.getFunction() != null && application.getFunction().getStatus().isActive();
    }

    private static boolean isPending(Application application) {
        return application != null && application.getFunction() != null && application.getFunction().getStatus().isPending();
    }

    private static void verifyApplication(ResourceDescription resource) {
        if (resource.isFolder() || resource.getType() != ResourceType.APPLICATION) {
            throw new IllegalArgumentException("Invalid application url: " + resource.getUrl());
        }
    }

    private static void verifyMapping(String path, boolean required, String message) {
        if (path == null) {
            if (required) {
                throw new IllegalArgumentException(message);
            }

            return;
        }

        if (!path.startsWith("/")) {
            throw new IllegalArgumentException(message);
        }

        try {
            UrlUtil.decodePath(path, true);
        } catch (Throwable e) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String buildMapping(String endpoint, String path) {
        return (endpoint == null || path == null) ? null : (endpoint + path);
    }

    private static boolean isPublicOrReview(ResourceDescription resource) {
        return resource.isPublic() || PublicationService.isReviewBucket(resource);
    }
}