package com.epam.aidial.core.server.service.dependency;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.data.folder.FolderResourceMarker;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.ApplicationSchemaService;
import com.epam.aidial.core.server.service.CatalogSchemaService;
import com.epam.aidial.core.server.service.config.ConfigEntityCodec;
import com.epam.aidial.core.server.service.dependency.DependencyTypePolicy.Content;
import com.epam.aidial.core.server.service.dependency.DiscoveredEdge.EdgeStatus;
import com.epam.aidial.core.server.service.dependency.DiscoveryResult.DiscoveryIssue;
import com.epam.aidial.core.server.service.resource.ComplexResourceService;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.event.Level;

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
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Lists, for each root, everything that it reaches through references. The graph is explored once for all the roots: each resource is
 * read once, and only its identity and the resources it refers to are kept, never its body. The list of a root is then a breadth-first
 * pass over those references, with no read.
 *
 * <p>An {@link DependencyAccessPolicy} decides, once for a whole level of the walk, which targets may be listed. A target that is not
 * listed is left out without a trace. A reference that is not followed is one log record with the resource key, the JSON pointer and a
 * reason, never the value; it is at DEBUG when the resource that holds the reference is in a user bucket. The instance holds no state of a
 * walk.
 */
@Slf4j
public final class DependencyWalker {

    private final Supplier<ConfigSnapshot> snapshotSource;
    private final ResourceService resourceService;
    private final ApplicationSchemaService applicationSchemaService;
    private final CatalogSchemaService catalogSchemaService;
    private final EncryptionService encryptionService;
    private final ComplexResourceService complexResourceService;
    private final PlatformSchemaScan platformSchemaScan;

    /**
     * @param snapshotSource what a walk resolves every name against, read once per walk
     */
    public DependencyWalker(Supplier<ConfigSnapshot> snapshotSource, ResourceService resourceService,
                            ApplicationSchemaService applicationSchemaService, CatalogSchemaService catalogSchemaService,
                            EncryptionService encryptionService, ComplexResourceService complexResourceService,
                            PlatformSchemaScan platformSchemaScan) {
        this.snapshotSource = snapshotSource;
        this.resourceService = resourceService;
        this.applicationSchemaService = applicationSchemaService;
        this.catalogSchemaService = catalogSchemaService;
        this.encryptionService = encryptionService;
        this.complexResourceService = complexResourceService;
        this.platformSchemaScan = platformSchemaScan;
    }

    private boolean isStored(ResourceDescriptor descriptor) {
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
     * @param roots the roots that the policy accepted; the same root named twice is walked once
     * @throws HttpException {@code 404} if a root does not exist
     */
    public DependencyListResponse walk(List<ResourceDescriptor> roots, DependencyAccessPolicy policy) {
        List<ResourceDescriptor> distinctRoots = List.copyOf(new LinkedHashSet<>(roots));
        ConfigSnapshot configuration = snapshotSource.get();
        DeploymentEdgeDiscovery discovery = new DeploymentEdgeDiscovery(configuration, resourceService, applicationSchemaService,
                catalogSchemaService, encryptionService, platformSchemaScan, policy::isSupported);
        long begun = System.nanoTime();
        Exploration exploration = new Exploration(discovery, configuration, policy);
        DependencyListResponse response = exploration.run(distinctRoots);
        log.info("Dependency list: roots={}, resources={}, duration={}ms", distinctRoots.size(), exploration.references.size(),
                (System.nanoTime() - begun) / 1_000_000);
        return response;
    }

    private record Referrer(ResourceDescriptor resource, String pointer) {
    }

    /** The stored body of a resource cannot be bound to its type; the message names the resource and holds no value of the body. */
    private static final class UnboundBodyException extends IllegalStateException {
        private UnboundBodyException(String key) {
            super("A stored body cannot be bound to its type (resource=" + key + ")");
        }
    }

    private final class Exploration {
        private final DeploymentEdgeDiscovery discovery;
        /** What discovery resolved against; the policy decides on the same publication. */
        private final ConfigSnapshot configuration;
        private final DependencyAccessPolicy policy;
        /** The resources that exist and were examined, each with the resources it refers to, in the order of its references. */
        private final Map<ResourceDescriptor, List<ResourceDescriptor>> references = new LinkedHashMap<>();
        /** The roots and every target met so far, listed by the policy or not: each is examined, or refused, once. */
        private final Set<ResourceDescriptor> seen = new HashSet<>();
        /** The first reference that led to each target, for the log of a target that does not exist. */
        private final Map<ResourceDescriptor, Referrer> referrers = new HashMap<>();

        private Exploration(DeploymentEdgeDiscovery discovery, ConfigSnapshot configuration, DependencyAccessPolicy policy) {
            this.discovery = discovery;
            this.configuration = configuration;
            this.policy = policy;
        }

        private DependencyListResponse run(List<ResourceDescriptor> roots) {
            seen.addAll(roots);
            List<ResourceDescriptor> level = roots;
            boolean isRootLevel = true;
            while (!level.isEmpty()) {
                Set<ResourceDescriptor> fresh = new LinkedHashSet<>();
                for (ResourceDescriptor descriptor : level) {
                    List<ResourceDescriptor> targets = examine(descriptor, isRootLevel);
                    if (targets == null) {
                        continue;
                    }
                    references.put(descriptor, targets);
                    for (ResourceDescriptor target : targets) {
                        if (seen.add(target)) {
                            fresh.add(target);
                        }
                    }
                }
                Set<ResourceDescriptor> listed = fresh.isEmpty() ? Set.of() : policy.selectListable(fresh, configuration);
                level = fresh.stream().filter(listed::contains).toList();
                isRootLevel = false;
            }
            return buildAnswer(roots);
        }

        /**
         * A root that does not exist or whose stored body cannot be bound ends the walk; a dependency that does not exist is logged and left
         * out, and one whose body cannot be bound is listed with unknown references.
         *
         * @return the targets that the references of the resource name, or {@code null} for a dependency that does not exist
         * @throws HttpException         {@code 404} if a root does not exist
         * @throws IllegalStateException if the stored body of a root cannot be bound
         */
        @Nullable
        private List<ResourceDescriptor> examine(ResourceDescriptor descriptor, boolean isRoot) {
            List<ResourceDescriptor> targets;
            try {
                targets = readTargets(descriptor);
            } catch (UnboundBodyException e) {
                if (isRoot) {
                    throw e;
                }
                logFinding(Level.WARN, descriptor, "Dependency list: the references of {} are not read: the stored body cannot be bound");
                return List.of();
            }
            if (targets == null) {
                if (isRoot) {
                    throw new HttpException(HttpStatus.NOT_FOUND, "A selected Application does not exist");
                }
                logMissing(descriptor);
            }
            return targets;
        }

        /**
         * @return the targets of the references of the resource, or {@code null} if the resource does not exist
         */
        @Nullable
        private List<ResourceDescriptor> readTargets(ResourceDescriptor descriptor) {
            Content content = DependencyTypePolicy.resolveContent(DependencyTypePolicy.resolveType(descriptor));
            if (content == Content.EXAMINED) {
                Deployment entity = loadDeployment(descriptor);
                return entity == null ? null : listTargets(descriptor, entity);
            }
            return isStored(descriptor) ? List.of() : null;
        }

        @Nullable
        private Deployment loadDeployment(ResourceDescriptor descriptor) {
            if (FileConfigEntities.isFile(descriptor)) {
                return FileConfigEntities.findDeployment(configuration.file(), descriptor);
            }
            String text = resourceService.getResource(descriptor);
            return text == null ? null : bindStored(descriptor, text);
        }

        private List<ResourceDescriptor> listTargets(ResourceDescriptor descriptor, Deployment entity) {
            DiscoveryResult found = discovery.discover(entity);
            for (DiscoveryIssue issue : found.issues()) {
                logFinding(Level.WARN, descriptor, "Dependency list: the references of {} may be incomplete: {} at {}",
                        DependencyResourceNames.formatReason(issue.kind()), issue.pointer());
            }
            Set<ResourceDescriptor> targets = new LinkedHashSet<>();
            for (DiscoveredEdge edge : found.edges()) {
                if (edge.status() != null) {
                    recordNotFollowed(descriptor, edge.pointer(), edge.status());
                    continue;
                }
                if (targets.add(edge.target())) {
                    referrers.putIfAbsent(edge.target(), new Referrer(descriptor, edge.pointer()));
                }
            }
            return List.copyOf(targets);
        }

        /**
         * Binds the stored body to the entity of its type. The exception names the resource only: no cause is kept, because the message of
         * the parser or of the binder can quote a value of the body.
         *
         * @throws UnboundBodyException if the body is not an object of that type
         */
        private Deployment bindStored(ResourceDescriptor descriptor, String text) {
            try {
                JsonNode tree = ConfigEntityCodec.BLOB_MAPPER.readTree(text);
                if (tree.isObject()) {
                    return ConfigEntityCodec.treeToEntity(tree, DependencyTypePolicy.resolveEntityClass(DependencyTypePolicy.resolveType(descriptor)));
                }
            } catch (JsonProcessingException | IllegalArgumentException e) {
                // falls through to the refusal below, without the cause
            }
            throw new UnboundBodyException(DependencyResourceNames.buildKey(descriptor));
        }

        /** Every target that is not a root was named by a reference that was recorded when it was found. */
        private void logMissing(ResourceDescriptor descriptor) {
            Referrer referrer = referrers.get(descriptor);
            logFinding(Level.WARN, referrer.resource(), "Dependency list: a reference is not followed: resource={} pointer={} reason={}",
                    referrer.pointer(), DependencyResourceNames.formatReason(EdgeStatus.MISSING));
        }

        private void recordNotFollowed(ResourceDescriptor from, String pointer, EdgeStatus status) {
            if (status == EdgeStatus.EXTERNAL) {
                // an external URL is not a resource of the system, so it is not a finding
                logFinding(Level.INFO, from, "Dependency list: a reference is external and not followed: resource={} pointer={}", pointer);
                return;
            }
            logFinding(Level.WARN, from, "Dependency list: a reference is not followed: resource={} pointer={} reason={}", pointer,
                    DependencyResourceNames.formatReason(status));
        }

        /**
         * Logs a finding about a resource at {@code operatorLevel}, or at DEBUG when the resource lies in a user bucket: its references are
         * the user's to fix, so a call must not make the operator read them. The key of the resource is the first argument of the message, and
         * it is built only when the record is written.
         */
        private void logFinding(Level operatorLevel, ResourceDescriptor resource, String format, Object... arguments) {
            boolean isUserResource = DependencyResourceNames.PRIVATE.equals(DependencyResourceNames.resolveOrigin(resource));
            Level level = isUserResource ? Level.DEBUG : operatorLevel;
            if (!log.isEnabledForLevel(level)) {
                return;
            }
            Object[] withKey = new Object[arguments.length + 1];
            withKey[0] = DependencyResourceNames.buildKey(resource);
            System.arraycopy(arguments, 0, withKey, 1, arguments.length);
            log.atLevel(level).log(format, withKey);
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
            String type = DependencyTypePolicy.resolveTypeName(DependencyTypePolicy.resolveType(descriptor));
            String url = policy.isUrlExposed(descriptor) ? descriptor.getUrl() : null;
            return new DependencyResource(type, url, descriptor.getName(), DependencyResourceNames.resolveOrigin(descriptor));
        }
    }
}
