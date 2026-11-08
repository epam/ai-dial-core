package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.config.InvalidEntityRecord;
import com.epam.aidial.core.server.data.folder.FolderResourceMarker;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.ExportArchive.BodyRedactor;
import com.epam.aidial.core.server.service.ExportClosureWalker.Closure;
import com.epam.aidial.core.server.service.ExportRequestParser.ExportRequest;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;

import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Everything that happens before the first response byte of an Application export, after the request is parsed: it
 * authorizes the public roots, confirms every root, takes the one configuration pair of the export and computes the
 * closure. It blocks: call it from a worker thread.
 * The explicit-admin gate is the caller's, and it also authorizes the roots in {@code platform/}.
 *
 * <p>The merged {@code Config} and the skipped entities are published by two separate writes, each under the lock that
 * every publication of the config store holds (the {@code ApiKeyStore} mutation lock that the store adopts as its rebuild
 * lock). They are read here under that lock, so the pair comes from one publication; the lock waits for a publication or
 * a whole rebuild in progress and is released before anything is resolved. The file-sourced config belongs to another
 * store that reloads on its own, so it is read afterwards and is not part of that guarantee.
 */
public final class ExportPlanner {

    private final Lock publicationLock;
    private final Supplier<Map<ResourceTypes, Map<String, InvalidEntityRecord>>> invalidEntitiesSource;
    private final Supplier<Config> configSource;
    private final Supplier<Config> fileConfigSource;
    private final ResourceService resourceService;
    private final ApplicationSchemaService applicationSchemaService;
    private final CatalogSchemaService catalogSchemaService;
    private final EncryptionService encryptionService;
    @Nullable
    private final BodyRedactor bodyRedactor;
    private final Function<ResourceDescriptor, FolderResourceMarker> skillMarkers;

    /**
     * @param publicationLock       the lock that every publication of the merged configuration holds
     * @param invalidEntitiesSource the skipped entities of the merged configuration
     * @param configSource          the merged configuration
     * @param fileConfigSource      the file-sourced configuration
     * @param bodyRedactor          redacts each JSON resource when the closure reads it; the closure keeps the redacted bytes, not the body;
     *                              {@code null} when no redaction policy is configured
     * @param skillMarkers          reads the marker of a Skill, or returns {@code null} when it does not exist (E6)
     */
    public ExportPlanner(Lock publicationLock, Supplier<Map<ResourceTypes, Map<String, InvalidEntityRecord>>> invalidEntitiesSource,
                         Supplier<Config> configSource, Supplier<Config> fileConfigSource,
                         ResourceService resourceService, ApplicationSchemaService applicationSchemaService,
                         CatalogSchemaService catalogSchemaService, EncryptionService encryptionService,
                         @Nullable BodyRedactor bodyRedactor, Function<ResourceDescriptor, FolderResourceMarker> skillMarkers) {
        this.skillMarkers = skillMarkers;
        this.publicationLock = publicationLock;
        this.invalidEntitiesSource = invalidEntitiesSource;
        this.configSource = configSource;
        this.fileConfigSource = fileConfigSource;
        this.resourceService = resourceService;
        this.applicationSchemaService = applicationSchemaService;
        this.catalogSchemaService = catalogSchemaService;
        this.encryptionService = encryptionService;
        this.bodyRedactor = bodyRedactor;
    }

    /**
     * @param canReadPublic whether the caller may read a resource in {@code public/}; it authorizes a public root and every
     *                      public dependency
     * @throws HttpException 403 if a public root is not readable, 404 if a root does not exist, 413 if the closure exceeds a
     *                       limit, 504 if the deadline passes
     */
    public Closure plan(ExportRequest request, Predicate<ResourceDescriptor> canReadPublic, ExportLimits limits,
                        ExportLimits.Deadline deadline) {
        for (ResourceDescriptor root : request.roots()) {
            if (ResourceDescriptor.PUBLIC_LOCATION.equals(root.getBucketLocation()) && !canReadPublic.test(root)) {
                throw new HttpException(HttpStatus.FORBIDDEN, "Forbidden");
            }
        }

        Map<ResourceTypes, Map<String, InvalidEntityRecord>> invalidEntities;
        Config merged;
        publicationLock.lock();
        try {
            invalidEntities = invalidEntitiesSource.get();
            merged = configSource.get();
        } finally {
            publicationLock.unlock();
        }
        Config fileConfig = fileConfigSource.get();
        DeploymentEdgeDiscovery discovery = new DeploymentEdgeDiscovery(merged, invalidEntities, fileConfig, resourceService,
                applicationSchemaService, catalogSchemaService, encryptionService, limits, deadline);
        try {
            return new ExportClosureWalker(resourceService, discovery, canReadPublic, bodyRedactor, limits, deadline,
                    descriptor -> ExportFileEntities.readTree(fileConfig, descriptor), skillMarkers)
                    .walk(request.roots());
        } catch (ResourceNotFoundException e) {
            throw new HttpException(HttpStatus.NOT_FOUND, "A selected Application does not exist");
        } catch (Exception e) {
            // not dead code: the schema retrieval rethrows an InterruptedException that the walk does not declare (sneaky throw)
            if (e instanceof InterruptedException) {
                // only the deadline interrupts the plan
                throw new HttpException(HttpStatus.GATEWAY_TIMEOUT, "The export took too long");
            }
            throw e;
        }
    }
}
