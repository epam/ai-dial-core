package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.config.InvalidEntityRecord;
import com.epam.aidial.core.server.data.folder.FolderResourceMarker;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.DependencyTypePolicy.Content;
import com.epam.aidial.core.server.service.DiscoveredEdge.EdgeStatus;
import com.epam.aidial.core.server.service.DiscoveryResult.DiscoveryIssue;
import com.epam.aidial.core.server.service.resource.ComplexResourceService;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Lists, for each root, everything that it reaches through references. The graph is explored once for all the roots: each resource is
 * read once, and only its identity and the resources it refers to are kept, never its body. The list of a root is then a breadth-first
 * pass over those references, with no read.
 *
 * <p>An {@link DependencyAccessPolicy} decides, once for a whole level of the walk, which targets may be listed. A target that is not
 * listed is left out without a trace. A reference that is not followed is one log record with the resource key, the JSON pointer and a
 * reason, never the value. The instance holds no state of a walk.
 */
@Slf4j
public final class DependencyWalker {

    private final Lock publicationLock;
    private final Supplier<Map<ResourceTypes, Map<String, InvalidEntityRecord>>> invalidEntitiesSource;
    private final Supplier<Config> configSource;
    private final Supplier<Config> fileConfigSource;
    private final ResourceService resourceService;
    private final ApplicationSchemaService applicationSchemaService;
    private final CatalogSchemaService catalogSchemaService;
    private final EncryptionService encryptionService;
    private final ComplexResourceService complexResourceService;

    /**
     * The merged {@code Config} and the skipped entities are read together under the publication lock, so they come from one publication.
     * The file-sourced configuration belongs to another store, so it is read afterwards and is not part of that guarantee.
     */
    public DependencyWalker(Lock publicationLock, Supplier<Map<ResourceTypes, Map<String, InvalidEntityRecord>>> invalidEntitiesSource,
                            Supplier<Config> configSource, Supplier<Config> fileConfigSource, ResourceService resourceService,
                            ApplicationSchemaService applicationSchemaService, CatalogSchemaService catalogSchemaService,
                            EncryptionService encryptionService, ComplexResourceService complexResourceService) {
        this.publicationLock = publicationLock;
        this.invalidEntitiesSource = invalidEntitiesSource;
        this.configSource = configSource;
        this.fileConfigSource = fileConfigSource;
        this.resourceService = resourceService;
        this.applicationSchemaService = applicationSchemaService;
        this.catalogSchemaService = catalogSchemaService;
        this.encryptionService = encryptionService;
        this.complexResourceService = complexResourceService;
    }

    /**
     * It blocks: call it from a worker thread.
     *
     * @return whether the stored resource exists; it is asked for a root, before the walk
     */
    public boolean isStored(ResourceDescriptor descriptor) {
        if (FileConfigEntities.isFile(descriptor)) {
            return true;
        }
        if (DependencyTypePolicy.resolveType(descriptor) == ResourceTypes.SKILL) {
            FolderResourceMarker marker = complexResourceService.getMarker(descriptor);
            return marker != null && marker.getCurrentVersion() != null && marker.getFileMetadata() != null && !marker.getFileMetadata().isEmpty();
        }
        return resourceService.hasResource(descriptor);
    }

    /**
     * It blocks: call it from a worker thread.
     *
     * @param roots the roots that the policy accepted and that exist; the same root named twice is walked once
     * @throws IllegalStateException if a root no longer exists
     */
    public DependencyListResponse walk(List<ResourceDescriptor> roots, DependencyAccessPolicy policy) {
        List<ResourceDescriptor> distinctRoots = List.copyOf(new LinkedHashSet<>(roots));
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
                applicationSchemaService, catalogSchemaService, encryptionService, policy::isSupported);
        long begun = System.nanoTime();
        Exploration exploration = new Exploration(discovery, fileConfig, policy);
        DependencyListResponse response = exploration.run(distinctRoots);
        log.info("Dependency list: roots={}, resources={}, duration={}ms", distinctRoots.size(), exploration.references.size(),
                (System.nanoTime() - begun) / 1_000_000);
        return response;
    }

    /** The resource and the JSON pointer of a reference. */
    private record Referrer(String key, String pointer) {
    }

    /** The state of one walk. */
    private final class Exploration {
        private final DeploymentEdgeDiscovery discovery;
        @Nullable
        private final Config fileConfig;
        private final DependencyAccessPolicy policy;
        /** The resources that exist and were examined, each with the listed resources it refers to, in the order of its references. */
        private final Map<ResourceDescriptor, List<ResourceDescriptor>> references = new LinkedHashMap<>();
        /** Every resource that was ever queued: the roots and the targets that the policy listed. */
        private final Set<ResourceDescriptor> met = new HashSet<>();
        private final Set<ResourceDescriptor> denied = new HashSet<>();
        /** The first reference that led to each target, for the log of a target that does not exist. */
        private final Map<ResourceDescriptor, Referrer> referrers = new HashMap<>();

        private Exploration(DeploymentEdgeDiscovery discovery, @Nullable Config fileConfig, DependencyAccessPolicy policy) {
            this.discovery = discovery;
            this.fileConfig = fileConfig;
            this.policy = policy;
        }

        private DependencyListResponse run(List<ResourceDescriptor> roots) {
            met.addAll(roots);
            List<ResourceDescriptor> level = roots;
            boolean isRootLevel = true;
            while (!level.isEmpty()) {
                Map<ResourceDescriptor, List<ResourceDescriptor>> found = new LinkedHashMap<>();
                Set<ResourceDescriptor> fresh = new LinkedHashSet<>();
                for (ResourceDescriptor descriptor : level) {
                    List<ResourceDescriptor> targets = examine(descriptor, isRootLevel);
                    if (targets == null) {
                        continue;
                    }
                    found.put(descriptor, targets);
                    for (ResourceDescriptor target : targets) {
                        if (!met.contains(target) && !denied.contains(target)) {
                            fresh.add(target);
                        }
                    }
                }
                Set<ResourceDescriptor> listed = fresh.isEmpty() ? Set.of() : policy.selectListable(fresh);
                List<ResourceDescriptor> next = new ArrayList<>();
                for (ResourceDescriptor target : fresh) {
                    if (listed.contains(target)) {
                        met.add(target);
                        next.add(target);
                    } else {
                        denied.add(target);
                    }
                }
                found.forEach((descriptor, targets) -> references.put(descriptor, targets.stream().filter(met::contains).toList()));
                level = next;
                isRootLevel = false;
            }
            return buildAnswer(roots);
        }

        /**
         * @return the targets that the references of the resource name, or {@code null} for a dependency that does not exist, which is logged
         * @throws IllegalStateException if a root no longer exists
         */
        @Nullable
        private List<ResourceDescriptor> examine(ResourceDescriptor descriptor, boolean isRoot) {
            String key = DependencyResourceNames.buildKey(descriptor);
            Content content = DependencyTypePolicy.resolveContent(DependencyTypePolicy.resolveType(descriptor));
            if (content == Content.EXAMINED) {
                return examineDeployment(descriptor, key, isRoot);
            }
            if (!isStored(descriptor)) {
                return onMissing(descriptor, key, isRoot);
            }
            return List.of();
        }

        @Nullable
        private List<ResourceDescriptor> examineDeployment(ResourceDescriptor descriptor, String key, boolean isRoot) {
            Deployment entity;
            if (FileConfigEntities.isFile(descriptor)) {
                entity = FileConfigEntities.findDeployment(fileConfig, descriptor);
            } else {
                String text = resourceService.getResource(descriptor);
                entity = text == null ? null : bindStored(descriptor, key, text, isRoot);
                if (entity == null && text != null) {
                    return List.of();
                }
            }
            if (entity == null) {
                return onMissing(descriptor, key, isRoot);
            }
            DiscoveryResult found = discovery.discover(entity);
            for (DiscoveryIssue issue : found.listReportedIssues()) {
                log.warn("Dependency list: the references of {} may be incomplete: {} at {}", key,
                        DependencyResourceNames.toWireName(issue.kind()), issue.pointer());
            }
            Set<ResourceDescriptor> targets = new LinkedHashSet<>();
            for (DiscoveredEdge edge : found.edges()) {
                if (edge.status() != null) {
                    recordNotFollowed(key, edge.pointer(), edge.status());
                    continue;
                }
                if (targets.add(edge.target())) {
                    referrers.putIfAbsent(edge.target(), new Referrer(key, edge.pointer()));
                }
            }
            return List.copyOf(targets);
        }

        /** A root whose body cannot be bound ends the walk; a dependency is listed with unknown references, and the log holds no cause. */
        @Nullable
        private Deployment bindStored(ResourceDescriptor descriptor, String key, String text, boolean isRoot) {
            try {
                return DependencyEntityBinder.bind(descriptor, text);
            } catch (IllegalStateException e) {
                if (isRoot) {
                    throw e;
                }
                log.warn("Dependency list: the references of {} are not read: the stored body cannot be bound", key);
                return null;
            }
        }

        /** A root that is gone ends the walk; a dependency that is gone is recorded and the walk goes on. */
        @Nullable
        private List<ResourceDescriptor> onMissing(ResourceDescriptor descriptor, String key, boolean isRoot) {
            if (isRoot) {
                throw new IllegalStateException("A selected Application no longer exists (resource=" + key + ")");
            }
            Referrer referrer = referrers.get(descriptor);
            log.warn("Dependency list: a reference is not followed: resource={} pointer={} reason={}",
                    referrer == null ? "-" : referrer.key(), referrer == null ? "-" : referrer.pointer(),
                    DependencyResourceNames.toWireName(EdgeStatus.MISSING));
            return null;
        }

        private void recordNotFollowed(String fromKey, String pointer, EdgeStatus status) {
            if (status == EdgeStatus.EXTERNAL) {
                // an external URL is not a resource of the system, so it is not a finding
                log.info("Dependency list: a reference is external and not followed: resource={} pointer={}", fromKey, pointer);
                return;
            }
            log.warn("Dependency list: a reference is not followed: resource={} pointer={} reason={}", fromKey, pointer,
                    DependencyResourceNames.toWireName(status));
        }

        private DependencyListResponse buildAnswer(List<ResourceDescriptor> roots) {
            List<DependencyRootEntry> entries = new ArrayList<>();
            for (ResourceDescriptor root : roots) {
                entries.add(new DependencyRootEntry(toResource(root), listDependencies(root)));
            }
            return new DependencyListResponse(List.copyOf(entries));
        }

        /** A breadth-first pass over the references that were found; the root is visited first, so it is never in its own list. */
        private List<DependencyResource> listDependencies(ResourceDescriptor root) {
            List<DependencyResource> dependencies = new ArrayList<>();
            Set<ResourceDescriptor> visited = new HashSet<>();
            visited.add(root);
            Deque<ResourceDescriptor> queue = new ArrayDeque<>();
            queue.add(root);
            for (ResourceDescriptor next = queue.poll(); next != null; next = queue.poll()) {
                for (ResourceDescriptor target : references.getOrDefault(next, List.of())) {
                    if (references.containsKey(target) && visited.add(target)) {
                        dependencies.add(toResource(target));
                        queue.add(target);
                    }
                }
            }
            return List.copyOf(dependencies);
        }

        private DependencyResource toResource(ResourceDescriptor descriptor) {
            String type = DependencyResourceNames.resolveTypeName(DependencyTypePolicy.resolveType(descriptor));
            String url = policy.isUrlExposed(descriptor) ? descriptor.getUrl() : null;
            return new DependencyResource(type, url, descriptor.getName(), DependencyResourceNames.resolveOrigin(descriptor));
        }
    }
}
