package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.data.folder.FolderResourceMarker;
import com.epam.aidial.core.server.service.DiscoveredEdge.EdgeStatus;
import com.epam.aidial.core.server.service.ExportArchive.BodyRedactor;
import com.epam.aidial.core.server.service.ExportDiscovery.DiscoveryIssue;
import com.epam.aidial.core.server.service.ExportTypePolicy.Content;
import com.epam.aidial.core.server.validation.CollectionIssueKind;
import com.epam.aidial.core.storage.data.FileMetadata;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * The worklist of the export. It reads the selected Applications, discovers the edges of each Deployment it reads (an Application, a
 * Model, an Interceptor or a ToolSet), and follows every candidate of these four types. A visited set keyed by the resolved descriptor
 * makes a resource reached by several paths, forms or cycles one node. A candidate that is a schema, a Prompt, a File or a Skill is read
 * and kept, but its content is not examined for references.
 *
 * <p>The roots are authorized by the request before they get here. A candidate in {@code platform/} needs no further check
 * (the explicit-admin gate was passed); a candidate in {@code public/} is checked by {@code canReadPublic} before it is read.
 * The {@link ExportLimits} bound the graph, the retained bodies and the time; a bound that is exceeded fails the export. The
 * instance is not thread-safe and serves one export.
 */
@Slf4j
public final class ExportClosureWalker {

    /**
     * @param content   what the plan kept of the resource: for stored JSON its identity, the redacted bytes that the archive writes and
     *                  their digest, never the stored body itself
     * @param discovery what was found in the body of a node whose body is examined; {@code null} for a node whose body is not
     *                  examined
     */
    public record ClosureNode(ResourceDescriptor descriptor, boolean isRoot, ExportNodeContent content,
                              @Nullable ExportDiscovery discovery) {

        /**
         * @return the issues of this node that are shown, none for a node whose body is not examined
         */
        public List<DiscoveryIssue> reportedIssues() {
            return discovery == null ? List.of() : discovery.reportedIssues();
        }
    }

    /**
     * A reference of one resource to another, as the closure settled it: it ends in a resource of the package, or it does not.
     */
    public sealed interface ClosureEdge {

        /**
         * @return the resource that holds the reference
         */
        ResourceDescriptor from();

        DiscoveredEdge discovered();

        /**
         * @param includedNode the descriptor of the node that the edge ends in
         */
        record Included(ResourceDescriptor from, DiscoveredEdge discovered, ResourceDescriptor includedNode) implements ClosureEdge {
        }

        /**
         * @param status the final status of an edge that does not end in an included resource
         */
        record Unresolved(ResourceDescriptor from, DiscoveredEdge discovered, EdgeStatus status) implements ClosureEdge {
        }

        static ClosureEdge included(ResourceDescriptor from, DiscoveredEdge discovered, ResourceDescriptor includedNode) {
            return new Included(from, discovered, includedNode);
        }

        static ClosureEdge unresolved(ResourceDescriptor from, DiscoveredEdge discovered, EdgeStatus status) {
            return new Unresolved(from, discovered, status);
        }
    }

    public record Closure(List<ClosureNode> nodes, List<ClosureEdge> edges) {

        /**
         * @return whether a Model or an Interceptor is in the closure, which is exported as its definition only
         */
        public boolean hasDefinitionOnlyBodies() {
            return nodes.stream().anyMatch(node -> ExportTypePolicy.isDefinitionOnly(ExportTypePolicy.typeOf(node.descriptor())));
        }

        /**
         * @return whether a Prompt is in the closure, whose body is exported as stored and whose references are not examined
         */
        public boolean hasAsStoredBodies() {
            return nodes.stream().anyMatch(node -> ExportTypePolicy.contentOf(ExportTypePolicy.typeOf(node.descriptor())) == Content.AS_STORED);
        }

        /**
         * @return whether a File or a Skill is in the closure, whose files are copied as stored and not examined
         */
        public boolean hasStreamedFiles() {
            return nodes.stream().anyMatch(node -> node.content().isCopiedAsStored());
        }

        public boolean isComplete() {
            return nodes.stream().allMatch(node -> node.discovery() == null || node.discovery().isComplete());
        }

        /**
         * @return the edges that do not end in an included resource, each with its final status
         */
        public List<ClosureEdge.Unresolved> unresolvedEdges() {
            return edges.stream().filter(ClosureEdge.Unresolved.class::isInstance).map(ClosureEdge.Unresolved.class::cast).toList();
        }
    }

    private final ResourceService resourceService;
    private final DeploymentEdgeDiscovery discovery;
    private final Predicate<ResourceDescriptor> canReadPublic;
    private final ExportLimits limits;
    private final ExportLimits.Deadline deadline;
    @Nullable
    private final BodyRedactor redactor;
    private final Function<ResourceDescriptor, JsonNode> fileEntities;
    private final Function<ResourceDescriptor, FolderResourceMarker> skillMarkers;

    /**
     * @param discovery     examines the stored body of every Application that the closure reads
     * @param canReadPublic whether the caller may read a resource in {@code public/}; for an explicit admin it holds
     * @param redactor      redacts each body when it is read, and the closure keeps the redacted bytes, not the stored body; {@code null}
     *                      when no redaction policy is configured, and then no archive can be written from the closure
     * @param fileEntities  reads the representation of an entity of the file configuration, or returns {@code null} when it is not there
     * @param skillMarkers  reads the marker of a Skill, which holds its pinned version and the size and etag of each of its files, or returns
     *                      {@code null} when the Skill does not exist
     */
    public ExportClosureWalker(ResourceService resourceService, DeploymentEdgeDiscovery discovery,
                               Predicate<ResourceDescriptor> canReadPublic, @Nullable BodyRedactor redactor, ExportLimits limits,
                               ExportLimits.Deadline deadline, Function<ResourceDescriptor, JsonNode> fileEntities,
                               Function<ResourceDescriptor, FolderResourceMarker> skillMarkers) {
        this.skillMarkers = skillMarkers;
        this.fileEntities = fileEntities;
        this.resourceService = resourceService;
        this.discovery = discovery;
        this.canReadPublic = canReadPublic;
        this.redactor = redactor;
        this.limits = limits;
        this.deadline = deadline;
    }

    /**
     * @param roots the selected Applications, already authorized
     * @throws ResourceNotFoundException if a root does not exist
     */
    public Closure walk(List<ResourceDescriptor> roots) {
        return new Walk().run(roots);
    }

    /**
     * The body of a node that is still to be examined, as it was read: the stored text of a resource, or the representation of an entity
     * of the file configuration, which has no text.
     */
    private record Pending(@Nullable String text, @Nullable JsonNode tree) {
    }

    /**
     * The state of one walk, so that the steps of it do not pass it around.
     */
    private final class Walk {
        private final Map<ResourceDescriptor, ExportNodeContent> contents = new LinkedHashMap<>();
        // the bodies of the nodes that are still to be examined; a body is dropped once its node has been examined
        private final Map<ResourceDescriptor, Pending> pending = new HashMap<>();
        private final Map<ResourceDescriptor, Integer> depths = new HashMap<>();
        private final Deque<ResourceDescriptor> worklist = new ArrayDeque<>();
        private long retainedChars;
        private long retainedBytes;

        private Closure run(List<ResourceDescriptor> roots) {
            Set<ResourceDescriptor> rootSet = new HashSet<>();
            for (ResourceDescriptor root : roots) {
                if (!ExportTypePolicy.isRoot(ExportTypePolicy.typeOf(root))) {
                    throw new IllegalArgumentException("A root of this type is not supported");
                }
                if (rootSet.add(root)) {
                    Pair<ResourceItemMetadata, String> stored = readOrThrow(root);
                    retain(root, stored.getRight(), stored.getLeft().getCreatedAt(), stored.getLeft().getUpdatedAt());
                    depths.put(root, 0);
                    worklist.add(root);
                }
            }

            Map<ResourceDescriptor, ExportDiscovery> discoveryByResource = new HashMap<>();
            List<ClosureEdge> closureEdges = new ArrayList<>();
            while (!worklist.isEmpty()) {
                deadline.check();
                ResourceDescriptor current = worklist.poll();
                ExportDiscovery found = discover(current, pending.remove(current), rootSet.contains(current));
                discoveryByResource.put(current, found);
                for (DiscoveredEdge edge : found.edges()) {
                    if (closureEdges.size() >= limits.maxEdges()) {
                        throw ExportLimits.exceeded("references");
                    }
                    closureEdges.add(resolve(current, edge));
                }
            }

            List<ClosureNode> nodes = new ArrayList<>();
            contents.forEach((descriptor, content) -> nodes.add(
                    new ClosureNode(descriptor, rootSet.contains(descriptor), content, discoveryByResource.get(descriptor))));
            return new Closure(List.copyOf(nodes), List.copyOf(closureEdges));
        }

        private ClosureEdge resolve(ResourceDescriptor from, DiscoveredEdge edge) {
            if (edge.status() != null) {
                return ClosureEdge.unresolved(from, edge, edge.status());
            }
            ResourceDescriptor target = edge.target().descriptor();
            if (contents.containsKey(target)) {
                return ClosureEdge.included(from, edge, target);
            }
            boolean isFileEntity = ExportFileEntities.isFile(target);
            ResourceTypes targetType = ExportTypePolicy.typeOf(target);
            if (!isFileEntity && !ExportTypePolicy.isBound(targetType, target.getBucketName())) {
                // the type is not allowed in that bucket: nothing is read
                return ClosureEdge.unresolved(from, edge, EdgeStatus.UNSUPPORTED);
            }
            if (ResourceDescriptor.PUBLIC_LOCATION.equals(target.getBucketLocation()) && !canReadPublic.test(target)) {
                // as for a public root: nothing is read and nothing is disclosed, and the answer is 403 before the first byte
                throw new HttpException(HttpStatus.FORBIDDEN, "Forbidden");
            }
            int depth = depths.get(from) + 1;
            if (depth > limits.maxDepth()) {
                throw ExportLimits.exceeded("depth");
            }
            deadline.check();
            if (!read(target, targetType, isFileEntity)) {
                return ClosureEdge.unresolved(from, edge, EdgeStatus.MISSING);
            }
            depths.put(target, depth);
            if (ExportTypePolicy.contentOf(targetType) == Content.EXAMINED) {
                worklist.add(target);
            }
            return ClosureEdge.included(from, edge, target);
        }

        /**
         * Reads what the export keeps of the target and adds it to the closure.
         *
         * @return whether the target exists
         */
        private boolean read(ResourceDescriptor target, ResourceTypes targetType, boolean isFileEntity) {
            if (ExportTypePolicy.contentOf(targetType) == Content.STREAMED) {
                // a File: only its metadata is read, so its content is never held, redacted or examined
                ResourceItemMetadata metadata = resourceService.getResourceMetadata(target);
                if (metadata == null) {
                    return false;
                }
                admit();
                long length = metadata instanceof FileMetadata file ? file.getContentLength() : 0;
                String contentType = metadata instanceof FileMetadata file ? file.getContentType() : null;
                contents.put(target, ExportNodeContent.ofStreamedFile(target, length, metadata.getEtag(), contentType,
                        metadata.getCreatedAt(), metadata.getUpdatedAt()));
                return true;
            }
            if (ExportTypePolicy.contentOf(targetType) == Content.COMPLEX) {
                // a Skill: only its marker is read, which pins the version and lists its files, so no file is read and none is examined
                FolderResourceMarker marker = skillMarkers.apply(target);
                if (marker == null || marker.getCurrentVersion() == null || marker.getFileMetadata() == null || marker.getFileMetadata().isEmpty()) {
                    return false;
                }
                admit();
                contents.put(target, ExportNodeContent.ofComplex(target, marker));
                return true;
            }
            if (isFileEntity) {
                JsonNode tree = fileEntities.apply(target);
                if (tree == null) {
                    return false;
                }
                retain(target, tree);
                return true;
            }
            Pair<ResourceItemMetadata, String> stored = resourceService.getResourceWithMetadata(target, EtagHeader.ANY);
            if (stored == null) {
                return false;
            }
            retain(target, stored.getRight(), stored.getLeft().getCreatedAt(), stored.getLeft().getUpdatedAt());
            return true;
        }

        /**
         * Discovers the references of a stored Deployment. A selected root whose body cannot be bound to its type makes the
         * parser throw {@link IllegalArgumentException}; it stays a fatal failure of the export (a 500 before the first byte, in both
         * modes), and only the resource is logged here, never the body, the parser message or a value.
         */
        private ExportDiscovery discover(ResourceDescriptor descriptor, Pending body, boolean isRoot) {
            try {
                ResourceTypes type = ExportTypePolicy.typeOf(descriptor);
                return body.tree() != null ? discovery.discover(type, body.tree()) : discovery.discover(type, requireContent(body.text()));
            } catch (IllegalArgumentException e) {
                if (isRoot) {
                    log.warn("Export failed: the stored resource {} cannot be parsed or bound", descriptor.getUrl());
                    throw e;
                }
                // a dependency is still exported, as redacted JSON; only its references cannot be read, so the discovery is incomplete
                log.warn("The stored dependency {} cannot be bound, so its references are not examined", descriptor.getUrl());
                return new ExportDiscovery(List.of(), List.of(new DiscoveryIssue(CollectionIssueKind.INSTANCE_INVALID, "")));
            }
        }

        /**
         * Redacts a body that was read and keeps the redacted bytes, unless that would exceed a bound. The stored body itself is kept only if
         * the node is to be examined, and only until it is.
         */
        private void retain(ResourceDescriptor descriptor, String body, @Nullable Long createdAt, @Nullable Long updatedAt) {
            admit();
            if (body.length() > limits.maxResourceChars()) {
                throw ExportLimits.exceeded("size of one resource");
            }
            retainedChars += body.length();
            if (retainedChars > limits.maxRetainedChars()) {
                throw ExportLimits.exceeded("total size");
            }
            contents.put(descriptor, keep(redact(descriptor, () -> ExportNodeContent.of(redactor, descriptor, body, createdAt, updatedAt))));
            keepIfExamined(descriptor, new Pending(body, null));
        }

        /**
         * The node of an entity of the file configuration: its representation goes to the redactor as it is, and is kept only if the node is to
         * be examined. It has no text, so the bounds of the size of a stored resource do not apply; the number of nodes does.
         */
        private void retain(ResourceDescriptor descriptor, JsonNode tree) {
            admit();
            contents.put(descriptor, keep(redact(descriptor, () -> ExportNodeContent.of(redactor, descriptor, tree))));
            keepIfExamined(descriptor, new Pending(null, tree));
        }

        /**
         * A body that the redactor refuses ends the export. This is the one record of the refusal: the redactor does not log it, and the
         * message holds the resource and the rule only.
         */
        private ExportNodeContent.Json redact(ResourceDescriptor descriptor, Supplier<ExportNodeContent.Json> content) {
            try {
                return content.get();
            } catch (IllegalStateException e) {
                // the redactor refuses with an IllegalStateException; any other failure propagates and is logged where it is answered
                log.warn("Export refused: {}", e.getMessage());
                throw e;
            }
        }

        /**
         * Counts the redacted bytes that the closure now holds until the archive is written: they are bounded by the same total as the stored
         * bodies.
         */
        private ExportNodeContent.Json keep(ExportNodeContent.Json json) {
            if (json.bytes() != null) {
                retainedBytes += json.bytes().length;
                if (retainedBytes > limits.maxRetainedChars()) {
                    throw ExportLimits.exceeded("total size");
                }
            }
            return json;
        }

        private void keepIfExamined(ResourceDescriptor descriptor, Pending body) {
            if (ExportTypePolicy.contentOf(ExportTypePolicy.typeOf(descriptor)) == Content.EXAMINED) {
                pending.put(descriptor, body);
            }
        }

        private void admit() {
            if (contents.size() >= limits.maxNodes()) {
                throw ExportLimits.exceeded("resources");
            }
        }
    }

    private Pair<ResourceItemMetadata, String> readOrThrow(ResourceDescriptor descriptor) {
        Pair<ResourceItemMetadata, String> stored = resourceService.getResourceWithMetadata(descriptor, EtagHeader.ANY);
        if (stored == null) {
            throw new ResourceNotFoundException("Application is not found: " + descriptor.getUrl());
        }
        return stored;
    }

    private static String requireContent(String body) {
        if (body.isEmpty()) {
            throw new IllegalStateException("A stored resource has no content");
        }
        return body;
    }
}
