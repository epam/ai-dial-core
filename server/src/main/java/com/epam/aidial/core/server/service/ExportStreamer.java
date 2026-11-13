package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.config.InvalidEntityRecord;
import com.epam.aidial.core.server.data.folder.FolderResourceMarker;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.DiscoveredEdge.EdgeStatus;
import com.epam.aidial.core.server.service.ExportDiscovery.DiscoveryIssue;
import com.epam.aidial.core.server.service.ExportRequestParser.ExportRequest;
import com.epam.aidial.core.server.service.ExportTypePolicy.Content;
import com.epam.aidial.core.server.service.resource.ComplexResourceService;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * The export of selected Applications as a stream, one resource at a time: read, bind, queue the references, decrypt, redact, serialize,
 * write, forget. Memory is the queue, the set of the resources met (each is exported once, so a cycle or a diamond ends) and the
 * resource in hand.
 *
 * <p>Deployments are followed through {@link DeploymentEdgeDiscovery}, each candidate marked as met before it is queued; schemas, Prompts,
 * Files and Skills are written but not examined. A reference that is not followed is one log record, never a value.
 *
 * <p>{@link #checkRoots} answers with a status before the response starts; a failure in {@link #stream} throws and cuts the stream.
 * The instance holds no state of an export.
 */
@Slf4j
public final class ExportStreamer {

    private final Lock publicationLock;
    private final Supplier<Map<ResourceTypes, Map<String, InvalidEntityRecord>>> invalidEntitiesSource;
    private final Supplier<Config> configSource;
    private final Supplier<Config> fileConfigSource;
    private final ResourceService resourceService;
    private final ApplicationSchemaService applicationSchemaService;
    private final CatalogSchemaService catalogSchemaService;
    private final EncryptionService encryptionService;
    private final ExportResourceProjector projector;
    private final ComplexResourceService complexResourceService;

    /**
     * The merged {@code Config} and the skipped entities are read together under the publication lock, so they come from one publication.
     * The file-sourced configuration belongs to another store, so it is read afterwards and is not part of that guarantee.
     */
    public ExportStreamer(Lock publicationLock, Supplier<Map<ResourceTypes, Map<String, InvalidEntityRecord>>> invalidEntitiesSource,
                          Supplier<Config> configSource, Supplier<Config> fileConfigSource, ResourceService resourceService,
                          ApplicationSchemaService applicationSchemaService, CatalogSchemaService catalogSchemaService,
                          EncryptionService encryptionService, ExportResourceProjector projector, ComplexResourceService complexResourceService) {
        this.publicationLock = publicationLock;
        this.invalidEntitiesSource = invalidEntitiesSource;
        this.configSource = configSource;
        this.fileConfigSource = fileConfigSource;
        this.resourceService = resourceService;
        this.applicationSchemaService = applicationSchemaService;
        this.catalogSchemaService = catalogSchemaService;
        this.encryptionService = encryptionService;
        this.projector = projector;
        this.complexResourceService = complexResourceService;
    }

    /**
     * What can still be a status: the public roots that the caller cannot read and the roots that do not exist. It blocks: call it from a
     * worker thread. The explicit-admin gate is the caller's.
     *
     * @throws HttpException 403 if a public root is not readable, 404 if a root does not exist
     */
    public void checkRoots(ExportRequest request, Predicate<ResourceDescriptor> canReadPublic) {
        for (ResourceDescriptor root : request.roots()) {
            if (ResourceDescriptor.PUBLIC_LOCATION.equals(root.getBucketLocation()) && !canReadPublic.test(root)) {
                throw new HttpException(HttpStatus.FORBIDDEN, "Forbidden");
            }
        }
        for (ResourceDescriptor root : request.roots()) {
            if (!resourceService.hasResource(root)) {
                throw new HttpException(HttpStatus.NOT_FOUND, "A selected Application does not exist");
            }
        }
    }

    /**
     * Writes the archive of the roots that {@link #checkRoots} accepted. It blocks until the last entry is written and does not close
     * {@code out}. If it throws, the archive is unfinished, so the consumer must treat what it received as truncated.
     *
     * @throws HttpException 413 if the export exceeds a limit, 403 if a public dependency is not readable, 504 if the deadline passes
     */
    public void stream(OutputStream out, ExportRequest request, Predicate<ResourceDescriptor> canReadPublic, ExportLimits limits,
                       ExportLimits.Deadline deadline, String coreVersion, Instant start) throws IOException {
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
        ExportArchive archive = new ExportArchive(out, resourceService, complexResourceService, deadline);
        Run run = new Run(archive, discovery, fileConfig, canReadPublic, limits, deadline);
        long begun = System.nanoTime();
        log.info("Export started: roots={}", request.roots().size());
        try {
            run.run(request.roots(), coreVersion, start);
            archive.finish();
            log.info("Export completed: duration={}ms, resources={}", computeElapsedMillis(begun), run.written);
        } catch (Exception e) {
            // not dead code: the schema retrieval rethrows an InterruptedException that the run does not declare (sneaky throw)
            Exception failure = e instanceof InterruptedException
                    ? new HttpException(HttpStatus.GATEWAY_TIMEOUT, "The export took too long") : e;
            // the message of our own exceptions names a resource or a bound, never a value; any other message is left out
            log.warn("Export aborted after the response started: resource={}, duration={}ms, {}{}", run.currentKey == null ? "-" : run.currentKey,
                    computeElapsedMillis(begun), failure.getClass().getSimpleName(),
                    failure instanceof HttpException || failure instanceof IllegalStateException ? ": " + failure.getMessage() : "");
            if (failure instanceof IOException io) {
                throw io;
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("The export failed", failure);
        } finally {
            archive.discard();
        }
    }

    private static long computeElapsedMillis(long beganNanos) {
        return (System.nanoTime() - beganNanos) / 1_000_000;
    }

    /**
     * A resource to write and the reference that led to it.
     *
     * @param referrer the key of the resource that holds the reference; {@code null} for a root
     */
    private record Queued(ResourceDescriptor descriptor, int depth, @Nullable String referrer, @Nullable String pointer) {
    }

    private final class Run {
        private final ExportArchive archive;
        private final DeploymentEdgeDiscovery discovery;
        @Nullable
        private final Config fileConfig;
        private final Predicate<ResourceDescriptor> canReadPublic;
        private final ExportLimits limits;
        private final ExportLimits.Deadline deadline;
        private final Deque<Queued> queue = new ArrayDeque<>();
        private final Set<ResourceDescriptor> met = new HashSet<>();
        private int written;
        private long edges;
        /** The key of the resource in hand, for the log of a failure; {@code null} before the first one and while its key is computed. */
        @Nullable
        private String currentKey;

        private Run(ExportArchive archive, DeploymentEdgeDiscovery discovery, @Nullable Config fileConfig,
                    Predicate<ResourceDescriptor> canReadPublic, ExportLimits limits, ExportLimits.Deadline deadline) {
            this.archive = archive;
            this.discovery = discovery;
            this.fileConfig = fileConfig;
            this.canReadPublic = canReadPublic;
            this.limits = limits;
            this.deadline = deadline;
        }

        private void run(Iterable<ResourceDescriptor> roots, String coreVersion, Instant start) throws IOException {
            archive.writeManifest(coreVersion, start);
            for (ResourceDescriptor root : roots) {
                if (met.add(root)) {
                    queue.add(new Queued(root, 0, null, null));
                }
            }
            for (Queued next = queue.poll(); next != null; next = queue.poll()) {
                deadline.check();
                write(next);
            }
        }

        private void write(Queued item) throws IOException {
            ResourceDescriptor descriptor = item.descriptor();
            boolean isFileEntity = ExportFileEntities.isFile(descriptor);
            currentKey = null;
            ExportResourceNames.requireSafePath(descriptor);
            String key = ExportResourceNames.buildKey(descriptor);
            currentKey = key;
            String entryName = isFileEntity ? ExportResourceNames.buildFragmentName(descriptor) : descriptor.getAbsoluteFilePath();
            Content content = ExportTypePolicy.resolveContent(ExportTypePolicy.resolveType(descriptor));
            switch (content) {
                case EXAMINED -> writeDeployment(item, key, entryName, isFileEntity);
                case SCHEMA -> writeText(item, key, entryName, isFileEntity, true);
                case AS_STORED -> writeText(item, key, entryName, isFileEntity, false);
                case STREAMED -> copyFile(item, key, entryName);
                case COMPLEX -> copySkill(item, key, entryName);
                default -> throw new IllegalStateException("A resource of this type is not exported (resource=" + key + ")");
            }
        }

        /** Sends the references to the queue before the entity is decrypted and written, so it is not held longer than that. */
        private void writeDeployment(Queued item, String key, String entryName, boolean isFileEntity) throws IOException {
            ResourceDescriptor descriptor = item.descriptor();
            Deployment entity;
            if (isFileEntity) {
                entity = ExportFileEntities.findDeployment(fileConfig, descriptor);
                if (entity == null) {
                    onMissing(item);
                    return;
                }
            } else {
                String text = read(item);
                if (text == null) {
                    return;
                }
                entity = projector.bind(descriptor, text);
            }
            admit();
            ExportDiscovery found = discovery.discover(entity);
            for (DiscoveryIssue issue : found.listReportedIssues()) {
                log.warn("Export: the references of {} may be incomplete: {} at {}", key, ExportResourceNames.toWireName(issue.kind()),
                        issue.pointer());
            }
            for (DiscoveredEdge edge : found.edges()) {
                follow(item, key, edge);
            }
            archive.writeJson(entryName, projector.projectDeployment(descriptor, entity));
        }

        /** A schema without the upstream keys of an application type schema, or a Prompt exactly as stored; neither is examined. */
        private void writeText(Queued item, String key, String entryName, boolean isFileEntity, boolean isSchema) throws IOException {
            ResourceDescriptor descriptor = item.descriptor();
            String text;
            if (isFileEntity) {
                text = ExportFileEntities.findSchemaText(fileConfig, descriptor);
                if (text == null) {
                    onMissing(item);
                    return;
                }
                checkSize(text);
            } else {
                text = read(item);
                if (text == null) {
                    return;
                }
            }
            admit();
            archive.writeJson(entryName, isSchema ? projector.projectSchema(descriptor, text) : text.getBytes(StandardCharsets.UTF_8));
            log.debug("Export: wrote {}", key);
        }

        private void copyFile(Queued item, String key, String entryName) throws IOException {
            ResourceItemMetadata metadata = resourceService.getResourceMetadata(item.descriptor());
            if (metadata == null) {
                onMissing(item);
                return;
            }
            admit();
            archive.copyFile(item.descriptor(), entryName, metadata.getEtag());
            log.debug("Export: copied {}", key);
        }

        private void copySkill(Queued item, String key, String entryName) throws IOException {
            FolderResourceMarker marker = complexResourceService.getMarker(item.descriptor());
            if (marker == null || marker.getCurrentVersion() == null || marker.getFileMetadata() == null || marker.getFileMetadata().isEmpty()) {
                onMissing(item);
                return;
            }
            admit();
            archive.copySkill(item.descriptor(), entryName, marker);
            log.debug("Export: copied {}", key);
        }

        /**
         * @return the stored text, or {@code null} for a dependency that does not exist, which is recorded
         * @throws IllegalStateException if a root no longer exists
         */
        @Nullable
        private String read(Queued item) {
            String text = resourceService.getResource(item.descriptor());
            if (text == null) {
                onMissing(item);
                return null;
            }
            checkSize(text);
            return text;
        }

        private void checkSize(String text) {
            if (text.length() > limits.maxResourceChars()) {
                throw ExportLimits.newExceededException("size of one resource");
            }
        }

        private void admit() {
            if (written >= limits.maxNodes()) {
                throw ExportLimits.newExceededException("resources");
            }
            written++;
        }

        /** A root that is gone ends the export; a dependency that is gone is recorded and the export goes on. */
        private void onMissing(Queued item) {
            if (item.referrer() == null) {
                throw new IllegalStateException("A selected Application no longer exists (resource=" + currentKey + ")");
            }
            log.warn("Export: a reference is not exported: resource={} pointer={} reason={}", item.referrer(), item.pointer(),
                    ExportResourceNames.toWireName(EdgeStatus.MISSING));
        }

        /**
         * Queues a reference once if its type and bucket are supported; a public dependency must be readable by the caller; anything else is
         * recorded in the log.
         */
        private void follow(Queued from, String fromKey, DiscoveredEdge edge) {
            if (++edges > limits.maxEdges()) {
                throw ExportLimits.newExceededException("references");
            }
            if (edge.status() != null) {
                recordNotFollowed(fromKey, edge.pointer(), edge.status());
                return;
            }
            ResourceDescriptor target = edge.target();
            if (met.contains(target)) {
                return;
            }
            boolean isFileEntity = ExportFileEntities.isFile(target);
            ResourceTypes targetType = ExportTypePolicy.resolveType(target);
            if (ExportTypePolicy.resolveContent(targetType) == null
                    || !isFileEntity && !ExportTypePolicy.isBound(targetType, target.getBucketName())) {
                // the type is not allowed in that bucket: nothing is read
                recordNotFollowed(fromKey, edge.pointer(), EdgeStatus.UNSUPPORTED);
                return;
            }
            if (ResourceDescriptor.PUBLIC_LOCATION.equals(target.getBucketLocation()) && !canReadPublic.test(target)) {
                // as for a public root: nothing is read and nothing is disclosed
                log.warn("Export: a public dependency is not readable by the caller: resource={} pointer={}", fromKey, edge.pointer());
                throw new HttpException(HttpStatus.FORBIDDEN, "Forbidden");
            }
            int depth = from.depth() + 1;
            if (depth > limits.maxDepth()) {
                throw ExportLimits.newExceededException("depth");
            }
            deadline.check();
            met.add(target);
            queue.add(new Queued(target, depth, fromKey, edge.pointer()));
        }

        private void recordNotFollowed(String fromKey, String pointer, EdgeStatus status) {
            if (status == EdgeStatus.EXTERNAL) {
                // an external URL is not a resource of the system, so it is not a finding
                log.info("Export: a reference is external and not exported: resource={} pointer={}", fromKey, pointer);
                return;
            }
            log.warn("Export: a reference is not exported: resource={} pointer={} reason={}", fromKey, pointer,
                    ExportResourceNames.toWireName(status));
        }
    }
}
