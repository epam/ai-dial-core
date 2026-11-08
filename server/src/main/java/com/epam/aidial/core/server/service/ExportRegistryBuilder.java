package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.data.ExportResourceIdentity;
import com.epam.aidial.core.server.service.DiscoveredEdge.EdgeStatus;
import com.epam.aidial.core.server.service.DiscoveredEdge.EdgeTarget;
import com.epam.aidial.core.server.service.ExportClosureWalker.Closure;
import com.epam.aidial.core.server.service.ExportClosureWalker.ClosureEdge;
import com.epam.aidial.core.server.service.ExportClosureWalker.ClosureNode;
import com.epam.aidial.core.server.service.ExportDiscovery.DiscoveryIssue;
import com.epam.aidial.core.server.service.ExportRegistry.Dependency;
import com.epam.aidial.core.server.service.ExportRegistry.Discovery;
import com.epam.aidial.core.server.service.ExportRegistry.FileRecord;
import com.epam.aidial.core.server.service.ExportRegistry.Issue;
import com.epam.aidial.core.server.service.ExportRegistry.Resource;
import com.epam.aidial.core.server.service.ExportRegistry.Skipped;
import com.epam.aidial.core.server.service.ExportRegistry.Summary;
import com.epam.aidial.core.server.service.ExportTypePolicy.Content;
import com.epam.aidial.core.server.service.ShortNameResolution.Origin;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Builds the dependency registry of an Application export from its closure. The output depends only on the closure, so two exports of
 * the same state give the same registry.
 */
public final class ExportRegistryBuilder {

    public static final String KIND = "dial-export-dependency-registry";
    public static final int FORMAT_VERSION = 1;
    /** The status of a dependency whose target is in the package. */
    public static final String INCLUDED = "included";

    private ExportRegistryBuilder() {
    }

    /**
     * What the archive preparation decided about one resource. The identity and the entry name are computed once, there, and
     * shared by the redactor, the archive entry and the registry.
     *
     */
    record Packaged(ExportResourceIdentity identity, String entryName) {
    }

    /**
     * @param nodes what was decided for each node of the closure, by descriptor, which is unique within a closure
     */
    public static ExportRegistry build(Closure closure, Map<ResourceDescriptor, Packaged> nodes) {
        List<ClosureEdge.Unresolved> unresolvedEdges = closure.unresolvedEdges();
        Map<ResourceDescriptor, List<Dependency>> dependenciesBySource = new HashMap<>();
        boolean isFileShadowing = false;
        for (ClosureEdge edge : closure.edges()) {
            dependenciesBySource.computeIfAbsent(edge.from(), key -> new ArrayList<>()).add(toDependency(edge, nodes));
            EdgeTarget shadowing = edge.discovered().target();
            if (shadowing != null && shadowing.hasShadowedEntity()) {
                isFileShadowing |= shadowing.origin() == Origin.FILE;
            }
        }

        List<String> roots = new ArrayList<>();
        Map<String, List<Dependency>> dependenciesByKey = new LinkedHashMap<>();
        List<FileRecord> files = new ArrayList<>();
        for (ClosureNode node : closure.nodes()) {
            String key = nodes.get(node.descriptor()).identity().key();
            if (node.content() instanceof ExportNodeContent.StreamedFile) {
                // a streamed file is listed in files and is not a resource of the registry; it has no references of its own
                Packaged packaged = nodes.get(node.descriptor());
                files.add(new FileRecord(key, null, packaged.identity().path(), packaged.entryName()));
                continue;
            }
            if (node.content() instanceof ExportNodeContent.Complex complex) {
                // a Skill is not a record of the registry either: each of its files is, and the Skill owns it
                Packaged packaged = nodes.get(node.descriptor());
                for (ExportNodeContent.ComplexFile file : complex.files()) {
                    files.add(new FileRecord(packaged.identity().skillFileKey(file.relativePath()), key,
                            packaged.identity().path() + "/" + file.relativePath(), packaged.entryName() + "/" + file.relativePath()));
                }
                continue;
            }
            if (node.isRoot()) {
                roots.add(key);
            }
            dependenciesByKey.put(key, List.copyOf(dependenciesBySource.getOrDefault(node.descriptor(), List.of())));
        }
        Map<String, List<String>> requiredByRoots = requiredByRoots(roots, dependenciesByKey);

        Map<String, Resource> resources = new LinkedHashMap<>();
        for (ClosureNode node : closure.nodes()) {
            if (node.content().isCopiedAsStored()) {
                continue;
            }
            Packaged packaged = nodes.get(node.descriptor());
            String key = packaged.identity().key();
            resources.put(key, Resource.of(packaged.identity(), node.isRoot() ? "selected" : "dependency", packaged.entryName(),
                    dependenciesByKey.get(key), List.copyOf(requiredByRoots.getOrDefault(key, List.of()))));
        }

        // the counts are those of the dependency records of the registry, so they cannot disagree with them
        Map<String, Integer> edgesByStatus = newStatusCounts();
        dependenciesByKey.values().forEach(list -> list.forEach(dependency -> edgesByStatus.computeIfPresent(dependency.status(), (status, count) -> count + 1)));

        List<Issue> issues = new ArrayList<>();
        for (ClosureNode node : closure.nodes()) {
            for (DiscoveryIssue issue : node.reportedIssues()) {
                issues.add(new Issue(nodes.get(node.descriptor()).identity().key(), ExportResourceIdentity.wireName(issue.kind()),
                        issue.pointer()));
            }
        }

        List<String> warnings = warningsFor(closure, unresolvedEdges, isFileShadowing);
        return new ExportRegistry(KIND, FORMAT_VERSION,
                List.copyOf(roots), resources, List.copyOf(files),
                new Discovery(closure.isComplete(), List.copyOf(issues)),
                new Summary(resources.size(), files.size(), warnings.size(), edgesByStatus), warnings);
    }

    /**
     * For every resource, the roots from which it is reached through included dependencies, not itself, in the order of {@code roots}.
     *
     * <p>A breadth-first walk from each root, with a visited set, so a cycle ends and a long chain cannot overflow the stack. The time is
     * O(R * (V + E)) for R roots, V resources and E included dependencies, which is small next to the storage reads of the closure at
     * the limits of the export (100 roots, 500 resources). The result lists every root of every resource that it reaches.
     */
    private static Map<String, List<String>> requiredByRoots(List<String> roots, Map<String, List<Dependency>> dependenciesByKey) {
        Map<String, List<String>> required = new HashMap<>();
        dependenciesByKey.keySet().forEach(key -> required.put(key, new ArrayList<>()));
        for (String root : roots) {
            Set<String> seen = new HashSet<>(Set.of(root));
            Deque<String> queue = new ArrayDeque<>(List.of(root));
            while (!queue.isEmpty()) {
                for (Dependency dependency : dependenciesByKey.get(queue.poll())) {
                    String target = dependency.key();
                    if (target != null && dependenciesByKey.containsKey(target) && seen.add(target)) {
                        required.get(target).add(root);
                        queue.add(target);
                    }
                }
            }
        }
        return required;
    }

    /**
     * @return the identity of the target of an edge; a schema target carries its {@code $id} as the target name
     */
    private static ExportResourceIdentity identityOfTarget(EdgeTarget target) {
        boolean isSchema = ExportTypePolicy.contentOf(target.type()) == Content.SCHEMA;
        @Nullable String schemaId = isSchema ? target.name() : null;
        if (target.origin() == Origin.FILE || target.descriptor() == null) {
            return ExportResourceIdentity.ofFileEntity(target.type(), target.name(), schemaId);
        }
        return ExportResourceIdentity.ofDescriptor(target.descriptor(), schemaId);
    }

    private static Dependency toDependency(ClosureEdge edge, Map<ResourceDescriptor, Packaged> nodes) {
        var discovered = edge.discovered();
        if (edge instanceof ClosureEdge.Included inclusion) {
            Packaged included = nodes.get(inclusion.includedNode());
            if (included == null) {
                throw new IllegalStateException("A dependency of an export ends in a resource that is not in the closure");
            }
            ExportResourceIdentity target = included.identity();
            return new Dependency(target.key(), target.type(), INCLUDED, discovered.pointer(), null, null, null);
        }
        EdgeStatus status = ((ClosureEdge.Unresolved) edge).status();
        List<Skipped> skipped = discovered.skipped().stream()
                .map(entry -> new Skipped(ExportResourceIdentity.typeName(entry.type()), entry.source())).toList();
        ExportResourceIdentity target = discovered.target() == null ? null : identityOfTarget(discovered.target());
        return new Dependency(null, target == null ? null : target.type(), publicStatus(status),
                discovered.pointer(), discovered.valueKind() == null ? null : ExportResourceIdentity.wireName(discovered.valueKind()),
                target, skipped);
    }

    /**
     * The status that the registry shows. A reference that does not end in an included resource is {@code unsupported} or
     * {@code external}, or {@code unresolved} for every other reason: nothing holds the name, the name or its origin is
     * ambiguous, the entity was skipped, or the schema could not be evaluated.
     */
    private static String publicStatus(EdgeStatus status) {
        return switch (status) {
            case UNSUPPORTED -> "unsupported";
            case EXTERNAL -> "external";
            case MISSING, UNRESOLVED_SHORT_NAME, ORIGIN_UNKNOWN, SCHEMA_UNAVAILABLE -> "unresolved";
        };
    }

    private static List<String> warningsFor(Closure closure, List<ClosureEdge.Unresolved> unresolvedEdges, boolean isFileShadowing) {
        List<String> warnings = new ArrayList<>();
        if (closure.hasDefinitionOnlyBodies()) {
            warnings.add("A Model or Interceptor is exported as its definition only; the endpoint, tokenizer and other external artifacts that it names are not exported.");
        }
        if (closure.hasAsStoredBodies()) {
            warnings.add("The body of an exported Prompt is exported as stored: nothing in it is removed or changed, and references inside it are not examined.");
        }
        if (closure.hasStreamedFiles()) {
            warnings.add("The content of an exported File, and of a file of an exported Skill, is copied as stored and is not examined for references.");
        }
        boolean hasUnresolvedShortName = unresolvedEdges.stream().anyMatch(edge -> edge.status() == EdgeStatus.UNRESOLVED_SHORT_NAME);
        if (hasUnresolvedShortName) {
            warnings.add("Only fixed fields and marked fields were examined; references in unmarked properties are not detected.");
        }
        if (isFileShadowing) {
            warnings.add("A file entity shadows a platform entity of the same type and name; the platform entity is not exported.");
        }
        return List.copyOf(warnings);
    }

    private static Map<String, Integer> newStatusCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String status : List.of("unsupported", "external", "unresolved")) {
            counts.put(status, 0);
        }
        return counts;
    }
}
