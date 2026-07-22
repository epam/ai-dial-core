package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.metaschemas.CopyAppBucketOptions;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.AutoSharedData;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.CatalogPropertiesLinkRewriter;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.validation.ApplicationTypeSchemaValidationException;
import com.epam.aidial.core.server.validation.CatalogSchemaValidationException;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.blobstore.BlobStorageUtil;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.epam.aidial.core.storage.util.UrlUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.commons.lang3.tuple.Pair;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

@Slf4j
public class ApplicationService {

    private static final String DEPLOYMENTS_NAME = "deployments";

    private static final String PUBLIC_DEPLOYMENTS_PREFIX = ResourceDescriptor.PUBLIC_BUCKET
            + ResourceDescriptor.PATH_SEPARATOR + DEPLOYMENTS_NAME + ResourceDescriptor.PATH_SEPARATOR;

    private final ConfigStore configStore;
    private final AsyncTaskExecutor taskExecutor;
    private final ApiKeyStore apiKeyStore;
    private final EncryptionService encryptionService;
    private final ExternalServiceService externalServiceService;
    private final ResourceService resourceService;
    private final LockService lockService;
    private final Supplier<String> idGenerator;
    private final RScoredSortedSet<String> pendingApplications;
    private final ApplicationOperatorService controller;
    private final long checkDelay;
    private final int checkSize;
    @Getter
    private final boolean includeCustomApps;

    private final ApplicationSchemaService applicationSchemaService;
    private final CatalogSchemaService catalogSchemaService;

    public ApplicationService(Vertx vertx,
                              AsyncTaskExecutor taskExecutor,
                              RedissonClient redis,
                              ApiKeyStore apiKeyStore,
                              EncryptionService encryptionService,
                              ExternalServiceService externalServiceService,
                              ResourceService resourceService,
                              LockService lockService,
                              ApplicationOperatorService operatorService,
                              ApplicationSchemaService applicationSchemaService,
                              CatalogSchemaService catalogSchemaService,
                              ConfigStore configStore,
                              Supplier<String> idGenerator,
                              JsonObject settings) {
        String pendingApplicationsKey = BlobStorageUtil.toStoragePath(lockService.getPrefix(), "pending-applications");

        this.taskExecutor = taskExecutor;
        this.apiKeyStore = apiKeyStore;
        this.encryptionService = encryptionService;
        this.externalServiceService = externalServiceService;
        this.resourceService = resourceService;
        this.applicationSchemaService = applicationSchemaService;
        this.catalogSchemaService = catalogSchemaService;
        this.configStore = configStore;
        this.lockService = lockService;
        this.idGenerator = idGenerator;
        this.pendingApplications = redis.getScoredSortedSet(pendingApplicationsKey, StringCodec.INSTANCE);
        this.controller = operatorService;
        this.checkDelay = settings.getLong("checkDelay", 300000L);
        this.checkSize = settings.getInteger("checkSize", 64);
        this.includeCustomApps = settings.getBoolean("includeCustomApps", false);

        if (controller.isActive()) {
            long checkPeriod = settings.getLong("checkPeriod", 300000L);
            vertx.setPeriodic(checkPeriod, checkPeriod, ignore -> taskExecutor.submit(this::checkApplications));
        }
    }

    private static String getTargetFolderForCustomAppFiles(ResourceDescriptor target) {
        if (target.isFolder()) {
            throw new IllegalArgumentException("Target url must be a file");
        }
        if (target.getType() != ResourceTypes.APPLICATION) {
            throw new IllegalArgumentException("Target url must be an application type");
        }
        String appName = target.getName();
        String appPath = target.getParentPath();
        if (appPath == null) {
            return "." + appName + ResourceDescriptor.PATH_SEPARATOR;
        } else {
            return appPath + ResourceDescriptor.PATH_SEPARATOR + "." + appName + ResourceDescriptor.PATH_SEPARATOR;
        }
    }

    public Pair<ResourceItemMetadata, Application> getApplication(ResourceDescriptor resource) {
        return getApplication(resource, EtagHeader.ANY);
    }

    public Pair<ResourceItemMetadata, Application> getApplication(ResourceDescriptor resource, EtagHeader etagHeader) {
        verifyApplication(resource);
        Pair<ResourceItemMetadata, String> result = resourceService.getResourceWithMetadata(resource, etagHeader);

        if (result == null) {
            throw new ResourceNotFoundException("Application is not found: " + resource.getUrl());
        }

        ResourceItemMetadata meta = result.getKey();
        Application application = ProxyUtil.convertToObject(result.getValue(), Application.class);

        if (application == null) {
            throw new ResourceNotFoundException("Application is not found: " + resource.getUrl());
        }

        application.setAuthor(meta.getAuthor());
        application.setCreatedAt(meta.getCreatedAt());
        application.setUpdatedAt(meta.getUpdatedAt());

        return Pair.of(meta, application);
    }

    public Application extractFrom(String content, ResourceItemMetadata meta) {
        Application application = ProxyUtil.convertToObject(content, Application.class);
        if (application == null) {
            throw new IllegalArgumentException("Application content is missed");
        }
        application.setAuthor(meta.getAuthor());
        application.setCreatedAt(meta.getCreatedAt());
        application.setUpdatedAt(meta.getUpdatedAt());
        return application;
    }

    public void putApplication(ResourceDescriptor resource, EtagHeader etag, String author,
                               Application application, boolean preserveForwardAuthToken,
                               AdminManagedFieldsWriteMode adminManagedFieldsWriteMode) {
        // In-memory callers (publication copy, admin apply) provide an authoritative application object.
        putApplication(resource, etag, author, application, preserveForwardAuthToken, adminManagedFieldsWriteMode,
                ExternalServicesWriteMode.OVERRIDE);
    }

    public Pair<ResourceItemMetadata, Application> putApplication(ResourceDescriptor resource, EtagHeader etag, String author,
                                                                   Application application, boolean preserveForwardAuthToken,
                                                                   AdminManagedFieldsWriteMode adminManagedFieldsWriteMode,
                                                                   ExternalServicesWriteMode externalServicesWriteMode) {
        prepareApplication(resource, application, preserveForwardAuthToken);

        MutableObject<List<String>> removedExternalServices = new MutableObject<>(List.of());
        ResourceItemMetadata meta = resourceService.computeResource(resource, etag, author, json -> {
            Application existing = ProxyUtil.convertToObject(json, Application.class);
            verifySchemaRichApp(application, existing);
            prepareApplicationFunction(resource, application, existing);
            prepareAdminManagedFields(application, existing, adminManagedFieldsWriteMode);
            List<String> externalServices = externalServiceService.processOnWrite(resource, application, existing, externalServicesWriteMode);
            removedExternalServices.setValue(externalServices);
            return ProxyUtil.convertToString(application);
        });

        // Purge credentials of services dropped by this write (after commit), like the dedicated DELETE.
        externalServiceService.purgeApplicationCredentials(resource, removedExternalServices.get());

        return Pair.of(meta, application);
    }

    // app_identity and allow_user_external_services are admin-managed: a field the mode does not honor is
    // inherited from the stored value on update (so a read-modify-write can't wipe it) and stripped on create.
    private static void prepareAdminManagedFields(Application application, Application existing, AdminManagedFieldsWriteMode mode) {
        if (!mode.honorAppIdentity()) {
            application.setAppIdentity(existing != null ? existing.getAppIdentity() : null);
        }
        if (!mode.honorAllowUserExternalServices()) {
            application.setAllowUserExternalServices(existing != null && existing.isAllowUserExternalServices());
        }
    }

    private void prepareApplicationFunction(ResourceDescriptor resource, Application application, Application existing) {
        Application.Function function = application.getFunction();
        if (function == null) {
            return;
        }
        if (existing == null || existing.getFunction() == null) {
            function.setId(UrlUtil.encodePathSegment(idGenerator.get()));
            function.setAuthorBucket(resource.getBucketName());
            function.setStatus(Application.Function.Status.UNDEPLOYED);
            function.setTargetFolder(encodeTargetFolder(resource, function.getId()));
        } else {
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

    public Pair<ResourceItemMetadata, Application> getApplicationWithDecryptedSecrets(ResourceDescriptor resource) {
        Pair<ResourceItemMetadata, Application> result = getApplication(resource);
        externalServiceService.decryptSecrets(resource, result.getValue());
        return result;
    }

    private static void verifySchemaRichApp(Application application, Application existing) {
        if (application.getApplicationTypeSchemaId() != null && existing != null
                && existing.getApplicationProperties() != null && application.getApplicationProperties() == null) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "The application with schema can not be updated to the one without properties");
        }
    }

    private void validateCatalogProperties(Application application) {
        try {
            catalogSchemaService.validate(application);
        } catch (CatalogSchemaValidationException e) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Catalog properties validation failed: " + e.getMessage(), e);
        }
    }

    public void deleteApplication(ResourceDescriptor resource, EtagHeader etag) {
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

        Application application = reference.get();

        if (application.getExternalServices() != null) {
            externalServiceService.purgeApplicationCredentials(resource, application.getExternalServices().keySet());
        }

        if (isPublicOrReview(resource)) {
            if (application.getFunction() != null) {
                deleteFolder(application.getFunction().getTargetFolder());
            }
            List<ResourceDescriptor> appFiles;
            try {
                appFiles = applicationSchemaService.getFiles(application);
            } catch (ApplicationTypeSchemaValidationException e) {
                appFiles = List.of();
            }
            for (ResourceDescriptor file : appFiles) {
                if (file.isFolder()) {
                    resourceService.deleteFolder(file);
                } else {
                    resourceService.deleteResource(file, EtagHeader.ANY);
                }
            }
        }
    }

    private ResourceDescriptor getAppFileBucket(ResourceDescriptor app) {
        String appBucketLocation = BucketBuilder.API_KEY_BUCKET_PATTERN.formatted(app.getUrl());
        String appBucket = Objects.requireNonNull(encryptionService.encrypt(appBucketLocation));
        return ResourceDescriptorFactory.fromDecoded(ResourceTypes.FILE, appBucket, appBucketLocation, ResourceDescriptor.PATH_SEPARATOR);
    }

    public void copyApplication(ResourceDescriptor source, ResourceDescriptor destination, String author, boolean overwrite, Consumer<Application> consumer) {
        verifyApplication(source);
        verifyApplication(destination);

        Pair<ResourceItemMetadata, Application> result = getApplication(source);
        Application application = result.getValue();

        externalServiceService.decryptSecrets(source, application);
        if (author == null) {
            author = result.getKey().getAuthor();
        }
        Application.Function function = application.getFunction();

        EtagHeader etag = overwrite ? EtagHeader.ANY : EtagHeader.NEW_ONLY;
        consumer.accept(application);
        application.setName(destination.getUrl());

        boolean isPublicOrReview = isPublicOrReview(destination);
        String sourceFolder = (function == null) ? null : function.getSourceFolder();

        Map<String, String> fileReplacementLinks;
        List<ResourceDescriptor> sourceAppFiles = List.of();
        List<ResourceDescriptor> destAppFiles = List.of();
        List<ResourceDescriptor> sourceCatalogFiles = List.of();
        List<ResourceDescriptor> destCatalogFiles = List.of();
        if (isPublicOrReview) {
            sourceAppFiles = applicationSchemaService.getFiles(application);
            destAppFiles = toDestAppFiles(source, destination, sourceAppFiles);
            sourceCatalogFiles = catalogSchemaService.getFiles(application);
            destCatalogFiles = toDestAppFiles(source, destination, sourceCatalogFiles);
            fileReplacementLinks = new HashMap<>();
            for (int i = 0; i < sourceAppFiles.size(); i++) {
                ResourceDescriptor sourceFile = sourceAppFiles.get(i);
                ResourceDescriptor destFile = destAppFiles.get(i);
                fileReplacementLinks.put(sourceFile.getDecodedUrl(), destFile.getUrl());
            }
            for (int i = 0; i < sourceCatalogFiles.size(); i++) {
                ResourceDescriptor sourceFile = sourceCatalogFiles.get(i);
                ResourceDescriptor destFile = destCatalogFiles.get(i);
                fileReplacementLinks.put(sourceFile.getDecodedUrl(), destFile.getUrl());
            }
        } else {
            fileReplacementLinks = Map.of();
        }

        resourceService.computeResource(destination, etag, author, json -> {
            Application existing = ProxyUtil.convertToObject(json, Application.class);

            // Same governance rule as putApplication: the source's admin-managed fields never travel through a
            // copy/move (a user could self-grant them by copying a public app), while an overwrite keeps whatever
            // an admin granted to the destination itself.
            prepareAdminManagedFields(application, existing, AdminManagedFieldsWriteMode.INHERIT_ONLY);

            verifySchemaRichApp(application, existing);
            validateCatalogProperties(application);

            if (function != null) {
                if (existing == null || existing.getFunction() == null) {
                    function.setId(UrlUtil.encodePathSegment(idGenerator.get()));
                    function.setStatus(Application.Function.Status.UNDEPLOYED);
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

            if (isPublicOrReview) {
                replaceLinksInAppProperties(application, fileReplacementLinks);
                application.setCatalogProperties(CatalogPropertiesLinkRewriter.rewrite(application.getCatalogProperties(), fileReplacementLinks));
            }

            externalServiceService.encryptSecrets(destination, application);

            return ProxyUtil.convertToString(application);
        });

        if (isPublicOrReview) {
            if (function != null) {
                // for public/review application source folder is equal to target folder
                // source files are copied to read-only deployment bucket for such applications
                copyFolder(sourceFolder, function.getSourceFolder());
            }
            copyResourceFiles(sourceAppFiles, destAppFiles);
            copyResourceFiles(sourceCatalogFiles, destCatalogFiles);
        }

        if (applicationSchemaService.getCopyAppBucketOptions(application) == CopyAppBucketOptions.ENABLED) {
            copyAppFileBucket(source, destination);
        }
    }

    private void copyResourceFiles(List<ResourceDescriptor> sourceFiles, List<ResourceDescriptor> destFiles) {
        for (int i = 0; i < sourceFiles.size(); i++) {
            ResourceDescriptor sourceFile = sourceFiles.get(i);
            ResourceDescriptor destFile = destFiles.get(i);
            if (sourceFile.isFolder()) {
                resourceService.copyFolder(sourceFile, destFile, false);
            } else {
                if (!resourceService.copyResource(sourceFile, destFile, null, false)) {
                    throw new IllegalArgumentException("Can't copy source file: " + sourceFile.getUrl()
                            + " to destination file: " + destFile.getUrl());
                }
            }
        }
    }

    private void copyAppFileBucket(ResourceDescriptor source, ResourceDescriptor destination) {
        ResourceDescriptor from = getAppFileBucket(source);
        ResourceDescriptor to = getAppFileBucket(destination);
        resourceService.copyFolder(from, to, false);
    }

    private static List<ResourceDescriptor> toDestAppFiles(ResourceDescriptor source, ResourceDescriptor dest, List<ResourceDescriptor> sourceAppFiles) {
        if (sourceAppFiles.isEmpty()) {
            return List.of();
        }
        String targetFolder = getTargetFolderForCustomAppFiles(dest);
        if (isPublicOrReview(source)) {
            return toDestAppFiles(dest, sourceAppFiles, targetFolder, ResourceDescriptor::getName);
        } else {
            Map<String, Integer> fileNameToCount = new HashMap<>();
            Function<ResourceDescriptor, String> fn = file -> createUniqueFileName(file, fileNameToCount);
            return toDestAppFiles(dest, sourceAppFiles, targetFolder, fn);
        }
    }

    private static List<ResourceDescriptor> toDestAppFiles(ResourceDescriptor dest,
                                                           List<ResourceDescriptor> sourceAppFiles, String targetFolder,
                                                           Function<ResourceDescriptor, String> fn) {
        List<ResourceDescriptor> result = new ArrayList<>();
        for (ResourceDescriptor file : sourceAppFiles) {
            String path = targetFolder + fn.apply(file);
            if (file.isFolder()) {
                path += ResourceDescriptor.PATH_SEPARATOR;
            }
            ResourceDescriptor target = ResourceDescriptorFactory.fromDecoded(
                    ResourceTypes.FILE,
                    dest.getBucketName(),
                    dest.getBucketLocation(),
                    path);
            result.add(target);
        }
        return result;
    }

    public Application redeployApplication(ProxyContext context, ResourceDescriptor resource) {
        verifyApplication(resource);
        controller.verifyActive();

        Pair<Application, Future<Void>> result = undeployApplicationInternal(resource);

        result.getValue().map(ignore -> deployApplication(context, resource))
                .onFailure(error -> log.error("Application redeployment is failed due to the error", error));
        return result.getKey();
    }

    public Application deployApplication(ProxyContext context, ResourceDescriptor resource) {
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

            application.getFunction().setStatus(Application.Function.Status.DEPLOYING);
            application.getFunction().setError(null);

            result.setValue(application);
            pendingApplications.add(System.currentTimeMillis() + checkDelay, resource.getUrl());

            return ProxyUtil.convertToString(application);
        });

        taskExecutor.submit(() -> launchApplication(context, resource))
                .onFailure(error -> taskExecutor.submit(() -> terminateApplication(resource, error.getMessage())));

        return result.get();
    }

    public Application undeployApplication(ResourceDescriptor resource) {
        return undeployApplicationInternal(resource).getKey();
    }

    private Pair<Application, Future<Void>> undeployApplicationInternal(ResourceDescriptor resource) {
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

            if (application.getFunction().getStatus() != Application.Function.Status.DEPLOYED) {
                throw new HttpException(HttpStatus.CONFLICT, "Application is not started: " + resource.getUrl());
            }

            application.setEndpoint(null);
            application.getFeatures().setRateEndpoint(null);
            application.getFeatures().setTokenizeEndpoint(null);
            application.getFeatures().setTruncatePromptEndpoint(null);
            application.getFeatures().setConfigurationEndpoint(null);
            application.getFunction().setStatus(Application.Function.Status.UNDEPLOYING);

            result.setValue(application);
            pendingApplications.add(System.currentTimeMillis() + checkDelay, resource.getUrl());

            return ProxyUtil.convertToString(application);
        });

        Future<Void> future = taskExecutor.submit(() -> terminateApplication(resource, null));
        return Pair.of(result.get(), future);
    }

    public Application.Logs getApplicationLogs(ResourceDescriptor resource) {
        verifyApplication(resource);
        controller.verifyActive();

        Application application = getApplication(resource).getValue();

        if (application.getFunction() == null || application.getFunction().getStatus() != Application.Function.Status.DEPLOYED) {
            throw new HttpException(HttpStatus.CONFLICT, "Application is not started: " + resource.getUrl());
        }

        return controller.getApplicationLogs(application.getFunction());
    }

    private void prepareApplication(ResourceDescriptor resource, Application application, boolean preserveForwardAuthToken) {
        verifyApplication(resource);
        URI applicationSchemaId = application.getApplicationTypeSchemaId();
        if (applicationSchemaId != null) {
            if (application.getEndpoint() != null || application.getFunction() != null || application.getMcp() != null) {
                throw new IllegalArgumentException("Neither application endpoint, MCP or function must be set for schema based application");
            }
            if (configStore.get().getCustomApplicationSchema(applicationSchemaId) == null) {
                throw new IllegalArgumentException("Application schema is not found by schema id: " + applicationSchemaId);
            }
        } else if (application.getEndpoint() == null && application.getFunction() == null
                && (application.getMcp() == null || application.getMcp().getEndpoint() == null)) {
            throw new IllegalArgumentException("At least application endpoint, MCP endpoint or function must be provided");
        }
        validateCatalogProperties(application);

        application.setName(resource.getUrl());
        application.setUserRoles(null);
        if (!preserveForwardAuthToken) {
            application.setForwardAuthToken(false);
        }

        if (application.getReference() == null) {
            application.setReference(ProxyUtil.generateReference());
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

            verifyMapping(function.getMapping().getChatCompletion(), true, "Application chat_completion mapping is missing/invalid");
            verifyMapping(function.getMapping().getRate(), false, "Application rate mapping is invalid");
            verifyMapping(function.getMapping().getTokenize(), false, "Application tokenize mapping is invalid");
            verifyMapping(function.getMapping().getTruncatePrompt(), false, "Application truncate_prompt mapping is invalid");
            verifyMapping(function.getMapping().getConfiguration(), false, "Application configuration mapping is invalid");

            if (function.getSourceFolder() == null) {
                throw new IllegalArgumentException("Application function source folder must be provided");
            }

            try {
                ResourceDescriptor folder = ResourceDescriptorFactory.fromAnyUrl(function.getSourceFolder(), encryptionService);

                if (!folder.isFolder() || folder.getType() != ResourceTypes.FILE
                        // admin may update the code app in the review stage
                        || (!folder.getBucketName().equals(resource.getBucketName()) && !isPublicOrReview(resource))) {
                    throw new IllegalArgumentException();
                }

                function.setSourceFolder(folder.getUrl());
            } catch (Throwable e) {
                throw new IllegalArgumentException("Application function sources must be a valid file folder: " + function.getSourceFolder());
            }
        }

        Application.Mcp mcp = application.getMcp();
        if (mcp != null) {
            if (mcp.getEndpoint() == null) {
                throw new IllegalArgumentException("MCP endpoint must be provided");
            }
        }
    }

    private Void checkApplications() {
        log.debug("Checking pending applications");
        try {
            long now = System.currentTimeMillis();

            for (String redisKey : pendingApplications.valueRange(Double.NEGATIVE_INFINITY, true, now, true, 0, checkSize)) {
                log.debug("Checking pending application: {}", redisKey);
                ResourceDescriptor resource = ResourceDescriptorFactory.fromAnyUrl(redisKey, encryptionService);

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

    private Void launchApplication(ProxyContext context, ResourceDescriptor resource) {
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

            if (function.getStatus() != Application.Function.Status.DEPLOYING) {
                throw new IllegalStateException("Application is not starting");
            }

            // for public/review application source folder is equal to target folder
            // source files are copied to read-only deployment bucket for such applications
            if (!Objects.equals(function.getSourceFolder(), function.getTargetFolder())) {
                copyFolder(function.getSourceFolder(), function.getTargetFolder());
            }

            ApiKeyData key = new ApiKeyData();
            key.getAttachedFolders().put(function.getTargetFolder(), new AutoSharedData(ResourceAccessType.READ_ONLY));

            ApiKeyData.initFromContext(key, context);
            apiKeyStore.assignPerRequestApiKey(key);

            try {
                controller.createApplicationImage(function, key);
            } finally {
                apiKeyStore.invalidatePerRequestApiKey(key);
            }

            String endpoint = controller.createApplicationDeployment(function);

            resourceService.computeResource(resource, json -> {
                Application existing = ProxyUtil.convertToObject(json, Application.class);
                if (existing == null || !Objects.equals(existing.getFunction(), application.getFunction())) {
                    throw new IllegalStateException("Application function has been updated");
                }

                function.setStatus(Application.Function.Status.DEPLOYED);
                existing.setFunction(function);
                existing.setEndpoint(buildMapping(endpoint, function.getMapping().getChatCompletion()));
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

    private Void terminateApplication(ResourceDescriptor resource, String error) {
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
                if (!Objects.equals(function.getSourceFolder(), function.getTargetFolder())) {
                    deleteFolder(function.getTargetFolder());
                }

                controller.deleteApplicationImage(function);
                controller.deleteApplicationDeployment(function);

                resourceService.computeResource(resource, json -> {
                    Application existing = ProxyUtil.convertToObject(json, Application.class);
                    if (existing == null || !Objects.equals(existing.getFunction(), function)) {
                        throw new IllegalStateException("Application function has been updated");
                    }

                    Application.Function.Status status = (function.getStatus() == Application.Function.Status.UNDEPLOYING)
                            ? Application.Function.Status.UNDEPLOYED
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

    private String deploymentLockKey(ResourceDescriptor resource) {
        return BlobStorageUtil.toStoragePath(lockService.getPrefix(), "deployment:" + resource.getAbsoluteFilePath());
    }

    private String encodeTargetFolder(ResourceDescriptor resource, String id) {
        String location = resource.getBucketLocation()
                + DEPLOYMENTS_NAME + ResourceDescriptor.PATH_SEPARATOR
                + id + ResourceDescriptor.PATH_SEPARATOR;

        String name = encryptionService.encrypt(location);
        return ResourceDescriptorFactory.fromDecoded(ResourceTypes.FILE, name, location, null).getUrl();
    }

    public static boolean isActive(Application application) {
        return application != null && application.getFunction() != null && application.getFunction().getStatus().isActive();
    }

    private static boolean isPending(Application application) {
        return application != null && application.getFunction() != null && application.getFunction().getStatus().isPending();
    }

    private static void verifyApplication(ResourceDescriptor resource) {
        if (resource.isFolder() || resource.getType() != ResourceTypes.APPLICATION) {
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

    private void copyFolder(String sourceFolderUrl, String targetFolderUrl) {
        ResourceDescriptor sourceFolder = ResourceDescriptorFactory.fromAnyUrl(sourceFolderUrl, encryptionService);
        ResourceDescriptor targetFolder = ResourceDescriptorFactory.fromAnyUrl(targetFolderUrl, encryptionService);
        resourceService.copyFolder(sourceFolder, targetFolder, false);
    }

    private void deleteFolder(String folderUrl) {
        ResourceDescriptor folder = ResourceDescriptorFactory.fromAnyUrl(folderUrl, encryptionService);
        resourceService.deleteFolder(folder);
    }

    private static String buildMapping(String endpoint, String path) {
        return (endpoint == null || path == null) ? null : (endpoint + path);
    }

    private static boolean isPublicOrReview(ResourceDescriptor resource) {
        return resource.isPublic() || PublicationService.isReviewBucket(resource);
    }

    public static void replaceLinksInAppProperties(Application application, Map<String, String> replacementLinks) {
        application.setApplicationProperties(CatalogPropertiesLinkRewriter.rewrite(application.getApplicationProperties(), replacementLinks));
    }

    private static String createUniqueFileName(ResourceDescriptor sourceDescriptor, Map<String, Integer> fileNamesTaken) {
        String fileName = sourceDescriptor.getName();
        int count = fileNamesTaken.getOrDefault(fileName, 0) + 1;
        fileNamesTaken.put(fileName, count);

        if (count > 1) {
            int index = fileName.lastIndexOf('.');
            if (sourceDescriptor.isFolder() || index == -1) {
                // File has no extension or folder
                fileName = fileName + "_" + count;
            } else {
                // File has extension
                fileName = fileName.substring(0, index) + "_" + count + fileName.substring(index);
            }
        }
        return fileName;
    }

    public static boolean isPublicApplicationSourceDirectory(ResourceDescriptor resource) {
        return resource.getBucketLocation().startsWith(PUBLIC_DEPLOYMENTS_PREFIX);
    }
}