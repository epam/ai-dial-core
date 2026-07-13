package com.epam.aidial.core.server.service.resource;

import com.epam.aidial.core.server.data.folder.FolderResourceMarker;
import com.epam.aidial.core.server.service.InvitationService;
import com.epam.aidial.core.server.service.ShareService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.blobstore.BlobStorageUtil;
import com.epam.aidial.core.storage.data.FileMetadata;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.data.NodeType;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagBuilder;
import com.epam.aidial.core.storage.util.EtagHeader;
import io.vertx.core.buffer.Buffer;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.annotation.Nullable;

/**
 * Generic engine for whole-resource (folder-as-resource) operations. A resource is materialized as a
 * set of immutable files under a {@code v/{versionId}/} prefix; the {@code .dial-resource} marker is a
 * pointer to the current version and the single commit point of a write.
 *
 * <p>Individual files (the version files and the marker) are managed through {@link ResourceService} so
 * they participate in the resource cache, etag tracking and event propagation; the engine never touches
 * blob storage directly.
 *
 * <p>Type-specific behavior (validation, marker metadata) is delegated to a {@link ComplexResourceHandler}.
 * The engine is transport-agnostic: HTTP request/response handling lives in the controller.
 */
@Slf4j
public class ComplexResourceService {

    public static final String MARKER_NAME = ".dial-resource";
    public static final String FOLDER_MARKER_NAME = ".dial-folder";

    private static final String VERSION_PREFIX = "v";
    private static final String FILES_SEGMENT = "files";
    private static final String FOLDER_TYPE = "folder";
    private static final int SCHEMA_VERSION = 1;
    private static final String STATE_ACTIVE = "active";
    private static final String STATE_DELETING = "deleting";
    private static final int PAGE_SIZE = 1000;
    // A file inside a resource must not be named after a structural token.
    private static final Set<String> RESERVED_SEGMENTS = Set.of(VERSION_PREFIX, MARKER_NAME, FOLDER_MARKER_NAME);
    // A DIAL resource or DIAL folder must not be named after a structural token.
    private static final Set<String> RESERVED_NAMES = Set.of(VERSION_PREFIX, MARKER_NAME, FOLDER_MARKER_NAME, FILES_SEGMENT);

    private final ResourceService resourceService;
    private final LockService lockService;
    private final ShareService shareService;
    private final InvitationService invitationService;

    public ComplexResourceService(ResourceService resourceService, LockService lockService,
                                 ShareService shareService, InvitationService invitationService) {
        this.resourceService = resourceService;
        this.lockService = lockService;
        this.shareService = shareService;
        this.invitationService = invitationService;
    }

    /**
     * Writes a whole resource: every uploaded file is stored under a fresh {@code v/{versionId}/} prefix,
     * the handler validates the file set, then the {@code .dial-resource} marker is committed with a single
     * guarded write. Returns the aggregate etag of the new version.
     *
     * @param uploads relative path -> file content (as received from the multipart body)
     */
    public String putFolder(ResourceDescriptor resource, ComplexResourceHandler handler,
                            Map<String, Buffer> uploads, EtagHeader etag, String author) {
        String name = resource.getName();
        if (name == null) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Resource name is missing");
        }
        if (RESERVED_NAMES.contains(name)) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Reserved resource name: " + name);
        }
        if (uploads.isEmpty()) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "At least one file is required");
        }

        // Sorted by relative path so the aggregate etag is deterministic regardless of part arrival order.
        Map<String, byte[]> files = new TreeMap<>();
        for (Map.Entry<String, Buffer> entry : uploads.entrySet()) {
            files.put(normalizePath(entry.getKey()), entry.getValue().getBytes());
        }

        handler.validate(files);

        // Creating a resource is a structural change: the bucket lock serializes the ancestor walk
        // (invariant enforcement + auto-vivify) with the marker write. Global lock order is
        // bucket-before-resource; the inner resource lock below is only ever taken while holding this.
        return lockService.underBucketLock(resource.getBucketLocation(), () -> {
            ensureStructuralPath(resource, ResourceKind.RESOURCE, author);

            ResourceDescriptor marker = markerDescriptor(resource);
            String versionId = UUID.randomUUID().toString().replace("-", "");

            try (LockService.Lock ignore = resourceService.lockResource(marker)) {
                // Honor If-Match / If-None-Match against the marker's aggregate etag before writing anything.
                etag.validate(readAggregateEtag(marker));

                try {
                    Map<String, String> fileEtags = new TreeMap<>();
                    for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                        String relativePath = entry.getKey();
                        byte[] content = entry.getValue();
                        ResourceDescriptor file = versionFile(resource, versionId, relativePath);
                        FileMetadata written = resourceService.putFile(file, content, EtagHeader.ANY,
                                BlobStorageUtil.getContentType(relativePath), author);
                        fileEtags.put(relativePath, written.getEtag());
                    }
                    FolderResourceMarker existing = readMarker(marker, false);
                    return commitMarker(marker, resource, versionId, aggregateEtag(fileEtags),
                            handler.buildMarkerMetadata(files), fileEtags, existing, author);
                } catch (Exception e) {
                    // Nothing is observable until the marker is committed; drop the orphan version files.
                    deleteVersion(versionFolder(resource, versionId));
                    throw e;
                }
            }
        });
    }

    /**
     * Streams a single file of the current version, or returns {@code null} if the resource is absent or
     * in the {@code deleting} state, or the file does not exist in the current version (both surface as 404).
     */
    @SneakyThrows
    public ResourceService.ResourceStream getFileStream(ResourceDescriptor resource, String relativePath) {
        String normalized = normalizePath(relativePath);
        FolderResourceMarker marker = getMarker(resource);
        if (marker == null) {
            return null;
        }
        ResourceDescriptor file = versionFile(resource, marker.getCurrentVersion(), normalized);
        return resourceService.getResourceStream(file, EtagHeader.ANY);
    }

    /**
     * Adds or replaces a single file via copy-on-write of a new version: the current version is copied
     * server-side to a fresh {@code v/{versionId}/} prefix, the single file is written, then the marker is
     * committed under the folder-scoped lock. Returns the new aggregate etag.
     */
    public String putFile(ResourceDescriptor resource, ComplexResourceHandler handler, String relativePath,
                          byte[] content, EtagHeader etag, String author) {
        String normalized = normalizePath(relativePath);
        handler.validateFileMutation(normalized, ComplexResourceHandler.FileMutation.PUT);
        return copyOnWrite(resource, etag, author, (newVersionId, fileEtags) -> {
            FileMetadata written = resourceService.putFile(versionFile(resource, newVersionId, normalized),
                    content, EtagHeader.ANY, BlobStorageUtil.getContentType(normalized), author);
            fileEtags.put(normalized, written.getEtag());
            // Only a change to a metadata-bearing file (e.g. the manifest) refreshes the marker metadata.
            return handler.refreshMetadataOnPut(normalized, content);
        });
    }

    /**
     * Removes a single file via copy-on-write of a new version. Returns the new aggregate etag.
     *
     * @throws HttpException NOT_FOUND if the file does not exist in the current version
     */
    public String deleteFile(ResourceDescriptor resource, ComplexResourceHandler handler, String relativePath,
                             EtagHeader etag, String author) {
        String normalized = normalizePath(relativePath);
        handler.validateFileMutation(normalized, ComplexResourceHandler.FileMutation.DELETE);
        return copyOnWrite(resource, etag, author, (newVersionId, fileEtags) -> {
            if (fileEtags.remove(normalized) == null) {
                throw new HttpException(HttpStatus.NOT_FOUND, "File not found: " + normalized);
            }
            resourceService.deleteResource(versionFile(resource, newVersionId, normalized), EtagHeader.ANY);
            // A deletable file never carries marker metadata (the manifest cannot be deleted).
            return null;
        });
    }

    /**
     * Shared copy-on-write engine for single-file mutations. Under the folder-scoped lock it validates the
     * {@code If-Match} precondition on the aggregate etag, copies the current version server-side to a fresh
     * one, applies the single change to the new version and commits the marker. The aggregate etag is
     * recomputed from the per-file etags carried in the marker, so no file content is read.
     *
     * @param mutation applies the change to the fresh version and to the per-file etag map, returning the
     *                 refreshed marker metadata, or {@code null} to keep the existing metadata
     */
    private String copyOnWrite(ResourceDescriptor resource, EtagHeader etag, String author, VersionMutation mutation) {
        ResourceDescriptor marker = markerDescriptor(resource);
        String newVersionId = UUID.randomUUID().toString().replace("-", "");

        try (LockService.Lock ignore = resourceService.lockResource(marker)) {
            FolderResourceMarker current = readMarker(marker, false);
            if (!isActive(current)) {
                throw new HttpException(HttpStatus.NOT_FOUND, "Resource not found: " + resource.getUrl());
            }
            etag.validate(current.getEtag());

            Map<String, String> fileEtags = currentFileEtags(resource, current);
            try {
                resourceService.copyFolder(versionFolder(resource, current.getCurrentVersion()),
                        versionFolder(resource, newVersionId), false);
                Map<String, Object> refreshed = mutation.apply(newVersionId, fileEtags);
                Map<String, Object> metadata = refreshed != null ? refreshed : current.getMetadata();

                String aggregateEtag = commitMarker(marker, resource, newVersionId, aggregateEtag(fileEtags),
                        metadata, fileEtags, current, author);
                deleteVersion(versionFolder(resource, current.getCurrentVersion()));
                return aggregateEtag;
            } catch (Exception e) {
                // Nothing is observable until the marker is committed; drop the orphan version files.
                deleteVersion(versionFolder(resource, newVersionId));
                throw e;
            }
        }
    }

    @FunctionalInterface
    private interface VersionMutation {
        @Nullable
        Map<String, Object> apply(String newVersionId, Map<String, String> fileEtags);
    }

    /**
     * Per-file etag map of the current version. Reconstructed from per-file metadata for legacy markers that
     * predate the stored map (still no file content is read).
     */
    private Map<String, String> currentFileEtags(ResourceDescriptor resource, FolderResourceMarker current) {
        if (current.getFiles() != null) {
            return new TreeMap<>(current.getFiles());
        }
        Map<String, String> fileEtags = new TreeMap<>();
        ResourceDescriptor versionFolder = versionFolder(resource, current.getCurrentVersion());
        for (ResourceDescriptor file : listVersionFiles(versionFolder)) {
            ResourceItemMetadata meta = resourceService.getResourceMetadata(file);
            if (meta != null) {
                fileEtags.put(versionFolder.getRelativePath(file), meta.getEtag());
            }
        }
        return fileEtags;
    }

    private String commitMarker(ResourceDescriptor marker, ResourceDescriptor resource, String versionId,
                                String aggregateEtag, Map<String, Object> metadata, Map<String, String> fileEtags,
                                @Nullable FolderResourceMarker existing, String author) {
        long now = System.currentTimeMillis();
        FolderResourceMarker document = new FolderResourceMarker();
        document.setType(resource.getType().group());
        document.setSchemaVersion(SCHEMA_VERSION);
        document.setState(STATE_ACTIVE);
        document.setCurrentVersion(versionId);
        document.setEtag(aggregateEtag);
        document.setFiles(fileEtags);
        document.setCreatedAt(existing == null ? now : existing.getCreatedAt());
        document.setUpdatedAt(now);
        document.setAuthor(author);
        document.setMetadata(metadata);

        String json = Objects.requireNonNull(ProxyUtil.convertToString(document));
        resourceService.putResource(marker, json, EtagHeader.ANY, author, false);
        return aggregateEtag;
    }

    /**
     * Aggregate etag derived from the per-file etags (relative path -> etag), sorted by path so the result
     * is deterministic regardless of insertion order.
     */
    private static String aggregateEtag(Map<String, String> fileEtags) {
        EtagBuilder builder = new EtagBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(fileEtags).entrySet()) {
            builder.append(entry.getKey().getBytes(StandardCharsets.UTF_8))
                    .append(entry.getValue().getBytes(StandardCharsets.UTF_8));
        }
        return builder.build();
    }

    /**
     * Returns the {@code .dial-resource} marker of the resource, or {@code null} if it does not exist
     * or is in the {@code deleting} state (treated as absent).
     */
    public FolderResourceMarker getMarker(ResourceDescriptor resource) {
        FolderResourceMarker marker = readMarker(markerDescriptor(resource), true);
        if (!isActive(marker)) {
            return null;
        }
        return marker;
    }

    /**
     * Resolves the {@code .dial-resource} marker for a whole-resource GET. Returns {@code null} if the path
     * is absent or in the {@code deleting} state (→ 404), or throws {@code 400} if the path is a DIAL folder
     * (clients must use the metadata listing for folders).
     */
    public FolderResourceMarker getResourceMarkerOrRejectFolder(ResourceDescriptor resource) {
        FolderResourceMarker marker = readMarker(markerDescriptor(resource), false);
        if (isActive(marker)) {
            return marker;
        }
        if (isActive(readMarker(folderMarkerDescriptor(resource), false))) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Path is a folder; use metadata listing: " + resource.getUrl());
        }
        return null;
    }

    /**
     * Creates a DIAL grouping folder ({@code .dial-folder} marker) under the bucket lock. Enforces the
     * structural invariants (auto-vivifying intermediate folders), then rejects if a resource or folder
     * already exists at the target. Returns the folder's synthetic etag.
     */
    public String createDialFolder(ResourceDescriptor resource, String author) {
        String name = resource.getName();
        if (name == null) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Folder name is missing");
        }
        if (RESERVED_NAMES.contains(name)) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Reserved folder name: " + name);
        }
        return lockService.underBucketLock(resource.getBucketLocation(), () -> {
            ensureStructuralPath(resource, ResourceKind.FOLDER, author);
            return commitFolderMarker(resource, author);
        });
    }

    /**
     * Deletes a DIAL grouping folder under the bucket lock, but only if it is empty (no active child
     * markers). Emptiness is not auto-cascaded. The tombstone marker write is the linearization point,
     * mirroring whole-resource delete.
     *
     * @throws HttpException NOT_FOUND if the folder is absent/deleting, PRECONDITION_FAILED on If-Match
     *                       mismatch, CONFLICT if the folder is not empty
     */
    public void deleteDialFolder(ResourceDescriptor resource, EtagHeader etag) {
        ResourceDescriptor marker = folderMarkerDescriptor(resource);
        lockService.underBucketLock(resource.getBucketLocation(), () -> {
            FolderResourceMarker current = readMarker(marker, false);
            if (!isActive(current)) {
                throw new HttpException(HttpStatus.NOT_FOUND, "Folder not found: " + resource.getUrl());
            }
            etag.validate(current.getEtag());
            if (!isEmptyFolder(resource)) {
                throw new HttpException(HttpStatus.CONFLICT, "Folder is not empty: " + resource.getUrl());
            }
            resourceService.deleteResource(marker, EtagHeader.ANY);
            return null;
        });
    }

    /**
     * Lists DIAL resources and grouping folders at a grouping level. A node is classified purely by the
     * presence of its marker file ({@code .dial-resource} → {@code ITEM}, {@code .dial-folder} →
     * {@code FOLDER}); the marker content is never read. Files that live inside a resource are excluded,
     * so only resource/folder nodes are returned.
     *
     * <p>When {@code recursive} is {@code true} the whole subtree is walked (as in the v1 metadata API);
     * otherwise only immediate children are returned. Pagination reuses the blob continuation token, so a
     * page may contain fewer nodes than {@code limit}.
     */
    public ResourceFolderMetadata listChildren(ResourceDescriptor groupingFolder, String token, int limit, boolean recursive) {
        // Always list recursively at the blob level: the marker files are the only signal of a DIAL node, and
        // they live one level below their node. The `recursive` flag then only controls the depth of nodes we
        // keep. Non-marker entries (resource files, version files) are excluded, so only nodes are returned.
        ResourceFolderMetadata raw = resourceService.getFolderMetadata(groupingFolder, token, limit, true);
        if (raw == null) {
            return null;
        }
        List<MetadataBase> items = new ArrayList<>();
        for (MetadataBase item : raw.getItems()) {
            NodeType nodeType = markerNodeType(item.getDescriptor().getName());
            if (nodeType == null) {
                // A resource file (or version file), not a marker -> excluded.
                continue;
            }
            String relativePath = groupingFolder.getRelativePath(item.getDescriptor());
            String[] segments = relativePath.split(ResourceDescriptor.PATH_SEPARATOR);
            // The grouping folder's own marker (no parent segment) is the listed folder itself, not a child.
            if (segments.length < 2) {
                continue;
            }
            // Non-recursive: keep only immediate children (node directly under the grouping folder).
            if (!recursive && segments.length > 2) {
                continue;
            }
            // "v" can only be a version prefix (it is a reserved name), so a "v" segment means the marker
            // lives inside a resource's version tree and does not denote a nested node.
            if (isInsideVersionTree(relativePath)) {
                continue;
            }
            items.add(nodeMetadata(item.getDescriptor().getParent(), nodeType, (ResourceItemMetadata) item));
        }
        return new ResourceFolderMetadata(groupingFolder, items, raw.getNextToken());
    }

    /**
     * Lists the files of a skill's current version (optionally under {@code subPath}), presented under the
     * clean {@code .../files/} url with the internal {@code v/{versionId}/} prefix hidden. Honors
     * {@code recursive} as in the v1 metadata API. Returns {@code null} if the resource is absent or in the
     * {@code deleting} state (→ 404).
     */
    public ResourceFolderMetadata listFiles(ResourceDescriptor resource, String subPath, String token, int limit, boolean recursive) {
        FolderResourceMarker marker = getMarker(resource);
        if (marker == null) {
            return null;
        }
        ResourceDescriptor versionFolder = versionFolder(resource, marker.getCurrentVersion());
        ResourceDescriptor listFolder = StringUtils.isBlank(subPath)
                ? versionFolder
                : subFolder(versionFolder, subPath);
        ResourceFolderMetadata raw = resourceService.getFolderMetadata(listFolder, token, limit, recursive);
        if (raw == null) {
            return new ResourceFolderMetadata(filesListingFolder(resource), List.of(), null);
        }
        List<MetadataBase> items = new ArrayList<>();
        for (MetadataBase item : raw.getItems()) {
            boolean folder = item.getNodeType() == NodeType.FOLDER;
            String relativePath = versionFolder.getRelativePath(item.getDescriptor());
            ResourceItemMetadata file = new ResourceItemMetadata(displayFileDescriptor(resource, relativePath, folder));
            if (item instanceof ResourceItemMetadata source) {
                file.setEtag(source.getEtag());
                file.setCreatedAt(source.getCreatedAt());
                file.setUpdatedAt(source.getUpdatedAt());
                file.setAuthor(source.getAuthor());
            }
            items.add(file);
        }
        return new ResourceFolderMetadata(filesListingFolder(resource), items, raw.getNextToken());
    }

    /**
     * Walks the ancestor prefixes of {@code target} under the bucket lock. A DIAL resource ancestor is
     * rejected (a resource must never live inside another resource); a markerless ancestor is auto-vivified
     * as a {@code .dial-folder}; an existing folder ancestor is left as-is. Then enforces the target
     * collision rules for {@code kind}.
     */
    private void ensureStructuralPath(ResourceDescriptor target, ResourceKind kind, String author) {
        List<String> parents = target.getParentFolders();
        for (int depth = 1; depth <= parents.size(); depth++) {
            ResourceDescriptor ancestor = ancestorDescriptor(target, depth);
            if (RESERVED_NAMES.contains(ancestor.getName())) {
                throw new HttpException(HttpStatus.BAD_REQUEST, "Reserved folder name: " + ancestor.getName());
            }
            NodeClass ancestorKind = classify(ancestor);
            if (ancestorKind == NodeClass.RESOURCE) {
                throw new HttpException(HttpStatus.BAD_REQUEST,
                        "Cannot create inside a DIAL resource: " + ancestor.getUrl());
            }
            if (ancestorKind == NodeClass.NONE) {
                // Markerless prefix: auto-vivify a grouping folder. An existing folder is left as-is.
                commitFolderMarker(ancestor, author);
            }
        }

        NodeClass targetKind = classify(target);
        if (kind == ResourceKind.FOLDER) {
            if (targetKind == NodeClass.FOLDER) {
                throw new HttpException(HttpStatus.BAD_REQUEST, "Folder already exists: " + target.getUrl());
            }
            if (targetKind == NodeClass.RESOURCE) {
                throw new HttpException(HttpStatus.BAD_REQUEST, "A resource already exists at this path: " + target.getUrl());
            }
        } else if (targetKind == NodeClass.FOLDER) {
            // An existing .dial-resource at the target is a normal new version (guarded by If-Match).
            throw new HttpException(HttpStatus.BAD_REQUEST, "A folder already exists at this path: " + target.getUrl());
        }
    }

    private boolean isEmptyFolder(ResourceDescriptor folder) {
        String token = null;
        do {
            ResourceFolderMetadata page = resourceService.getFolderMetadata(folder, token, PAGE_SIZE, false);
            if (page == null) {
                return true;
            }
            for (MetadataBase item : page.getItems()) {
                if (item.getNodeType() != NodeType.FOLDER) {
                    continue;
                }
                ResourceDescriptor child = folder.resolveByUrl(item.getUrl());
                if (RESERVED_NAMES.contains(child.getName())) {
                    continue;
                }
                if (classify(child) != NodeClass.NONE) {
                    return false;
                }
            }
            token = page.getNextToken();
        } while (token != null);
        return true;
    }

    private String commitFolderMarker(ResourceDescriptor resource, String author) {
        ResourceDescriptor marker = folderMarkerDescriptor(resource);
        FolderResourceMarker existing = readMarker(marker, false);
        long now = System.currentTimeMillis();
        FolderResourceMarker document = new FolderResourceMarker();
        document.setType(FOLDER_TYPE);
        document.setSchemaVersion(SCHEMA_VERSION);
        document.setState(STATE_ACTIVE);
        // A folder has no file content, so synthesize an etag to support If-Match on folder delete.
        document.setEtag(EtagBuilder.generateEtag(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)));
        document.setCreatedAt(existing == null ? now : existing.getCreatedAt());
        document.setUpdatedAt(now);
        document.setAuthor(author);
        resourceService.putResource(marker, Objects.requireNonNull(ProxyUtil.convertToString(document)),
                EtagHeader.ANY, author);
        return document.getEtag();
    }

    /**
     * Builds a listing item for a DIAL node from its marker's listing metadata (timestamps/author), without
     * reading the marker file content. The {@code nodeType} ({@code ITEM} for a resource, {@code FOLDER} for a
     * grouping folder) already conveys the kind. The aggregate etag is not included: it lives inside the marker
     * and is available via a whole-resource GET.
     */
    private static ResourceItemMetadata nodeMetadata(ResourceDescriptor node, NodeType nodeType, ResourceItemMetadata marker) {
        ResourceItemMetadata metadata = new ResourceItemMetadata(node);
        metadata.setNodeType(nodeType);
        metadata.setCreatedAt(marker.getCreatedAt());
        metadata.setUpdatedAt(marker.getUpdatedAt());
        metadata.setAuthor(marker.getAuthor());
        return metadata;
    }

    // A DIAL resource is reported as an ITEM (a whole resource addressed as a unit), a DIAL grouping folder
    // as a FOLDER. Returns null for any non-marker entry (a plain file inside a resource).
    @Nullable
    private static NodeType markerNodeType(String name) {
        if (MARKER_NAME.equals(name)) {
            return NodeType.ITEM;
        }
        if (FOLDER_MARKER_NAME.equals(name)) {
            return NodeType.FOLDER;
        }
        return null;
    }

    private static boolean isInsideVersionTree(String relativeMarkerPath) {
        String[] segments = relativeMarkerPath.split(ResourceDescriptor.PATH_SEPARATOR);
        // The last segment is the marker filename; a "v" among the parents is a version prefix.
        for (int i = 0; i < segments.length - 1; i++) {
            if (VERSION_PREFIX.equals(segments[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * Probes a node for its marker (reading the marker to honor its lifecycle state): an active
     * {@code .dial-resource} is a DIAL resource, otherwise an active {@code .dial-folder} is a DIAL folder,
     * otherwise it is a plain (markerless) folder. A {@code deleting} marker counts as absent. Used by the
     * structural invariant walk and folder-emptiness check, where {@code deleting} must be treated as absent.
     */
    private NodeClass classify(ResourceDescriptor node) {
        if (isActive(readMarker(markerDescriptor(node), false))) {
            return NodeClass.RESOURCE;
        }
        if (isActive(readMarker(folderMarkerDescriptor(node), false))) {
            return NodeClass.FOLDER;
        }
        return NodeClass.NONE;
    }

    private enum ResourceKind {
        RESOURCE, FOLDER
    }

    private enum NodeClass {
        RESOURCE, FOLDER, NONE
    }

    /**
     * Tombstones the resource by flipping the {@code .dial-resource} marker to {@code state: deleting},
     * then best-effort removes the version tree and marker. If the marker does not exist the method
     * returns without doing anything (idempotent).
     *
     * <p>The tombstone write (via {@link ResourceService#computeResource}) is the linearization point:
     * once committed, all read paths return 404 even if the version tree still physically exists.
     *
     * @throws HttpException PRECONDITION_FAILED if the {@code If-Match} header does not match
     */
    public void deleteFolder(ResourceDescriptor resource, EtagHeader etag) {
        ResourceDescriptor marker = markerDescriptor(resource);

        // DELETE is structural (it may affect ancestor-folder emptiness). Take the bucket lock so the
        // tombstone commit and access-control cleanup happen atomically; the inner resource lock taken by
        // computeResource is nested inside it (bucket-before-resource, the global order).
        MutableObject<String> capturedVersion = new MutableObject<>();
        lockService.underBucketLock(resource.getBucketLocation(), () -> {
            resourceService.computeResource(marker, json -> {
                if (json == null) {
                    throw new HttpException(HttpStatus.NOT_FOUND, "Resource not found: " + resource.getUrl());
                }
                FolderResourceMarker current = ProxyUtil.convertToObject(json, FolderResourceMarker.class);
                if (!isActive(current)) {
                    throw new HttpException(HttpStatus.NOT_FOUND, "Resource not found: " + resource.getUrl());
                }
                etag.validate(current.getEtag());
                capturedVersion.setValue(current.getCurrentVersion());
                current.setState(STATE_DELETING);
                current.setDeletedAt(System.currentTimeMillis());
                return Objects.requireNonNull(ProxyUtil.convertToString(current));
            });

            if (resource.isPrivate()) {
                String bucketName = resource.getBucketName();
                String bucketLocation = resource.getBucketLocation();
                invitationService.cleanUpResourceLink(bucketName, bucketLocation, resource);
                shareService.revokeSharedResource(bucketName, bucketLocation, resource);
            }
            return null;
        });

        if (capturedVersion.get() != null) {
            ResourceDescriptor folderVersion = versionFolder(resource, capturedVersion.get());
            deleteVersion(folderVersion);
            resourceService.deleteResource(marker, EtagHeader.ANY);
        }
    }

    @SneakyThrows
    public Buffer downloadArchive(ResourceDescriptor resource, String version) {
        ResourceDescriptor versionFolder = versionFolder(resource, version);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive)) {
            for (ResourceDescriptor file : listVersionFiles(versionFolder)) {
                try (ResourceService.ResourceStream resourceStream = resourceService.getResourceStream(file, EtagHeader.ANY)) {
                    if (resourceStream == null) {
                        continue;
                    }
                    zip.putNextEntry(new ZipEntry(versionFolder.getRelativePath(file)));
                    try (InputStream input = resourceStream.inputStream()) {
                        input.transferTo(zip);
                    }
                    zip.closeEntry();
                }
            }
        }
        return Buffer.buffer(archive.toByteArray());
    }

    private List<ResourceDescriptor> listVersionFiles(ResourceDescriptor versionFolder) {
        List<ResourceDescriptor> files = new ArrayList<>();
        String token = null;
        do {
            ResourceFolderMetadata folder = resourceService.getFolderMetadata(versionFolder, token, PAGE_SIZE, true);
            if (folder == null) {
                break;
            }
            for (MetadataBase item : folder.getItems()) {
                files.add(item.getDescriptor());
            }
            token = folder.getNextToken();
        } while (token != null);
        return files;
    }

    private void deleteVersion(ResourceDescriptor versionFolder) {
        try {
            for (ResourceDescriptor file : listVersionFiles(versionFolder)) {
                resourceService.deleteResource(file, EtagHeader.ANY);
            }
        } catch (Exception e) {
            log.warn("Failed to clean up version: {}", versionFolder.getUrl(), e);
        }
    }

    private String readAggregateEtag(ResourceDescriptor marker) {
        FolderResourceMarker document = readMarker(marker, false);
        return document == null ? null : document.getEtag();
    }

    private FolderResourceMarker readMarker(ResourceDescriptor marker, boolean lock) {
        String json = resourceService.getResource(marker, EtagHeader.ANY, lock);
        if (json == null) {
            return null;
        }
        return ProxyUtil.convertToObject(json, FolderResourceMarker.class);
    }

    private static ResourceDescriptor markerDescriptor(ResourceDescriptor resource) {
        List<String> parentFolders = new ArrayList<>(resource.getParentFolders());
        parentFolders.add(resource.getName());
        return new ResourceDescriptor(resource.getType(), MARKER_NAME, parentFolders,
                resource.getBucketName(), resource.getBucketLocation(), false);
    }

    private static ResourceDescriptor folderMarkerDescriptor(ResourceDescriptor resource) {
        List<String> parentFolders = new ArrayList<>(resource.getParentFolders());
        parentFolders.add(resource.getName());
        return new ResourceDescriptor(resource.getType(), FOLDER_MARKER_NAME, parentFolders,
                resource.getBucketName(), resource.getBucketLocation(), false);
    }

    /**
     * Ancestor prefix of {@code resource} at the given depth (1-based over the parent folders). Returned as a
     * non-folder descriptor whose name is the ancestor's last segment, so marker descriptors can be built from it.
     */
    private static ResourceDescriptor ancestorDescriptor(ResourceDescriptor resource, int depth) {
        List<String> parents = resource.getParentFolders();
        List<String> ancestorParents = new ArrayList<>(parents.subList(0, depth - 1));
        return new ResourceDescriptor(resource.getType(), parents.get(depth - 1), ancestorParents,
                resource.getBucketName(), resource.getBucketLocation(), false);
    }

    /** Folder descriptor for a subfolder inside a version folder, used to scope a file listing. */
    private static ResourceDescriptor subFolder(ResourceDescriptor versionFolder, String subPath) {
        List<String> segments = Arrays.asList(StringUtils.strip(subPath, ResourceDescriptor.PATH_SEPARATOR)
                .split(ResourceDescriptor.PATH_SEPARATOR));
        List<String> parentFolders = new ArrayList<>(versionFolder.getParentFolders());
        parentFolders.add(versionFolder.getName());
        parentFolders.addAll(segments.subList(0, segments.size() - 1));
        return new ResourceDescriptor(versionFolder.getType(), segments.getLast(), parentFolders,
                versionFolder.getBucketName(), versionFolder.getBucketLocation(), true);
    }

    /** Synthetic {@code .../files/} folder descriptor for a file listing response (the version prefix is hidden). */
    private static ResourceDescriptor filesListingFolder(ResourceDescriptor resource) {
        List<String> parentFolders = new ArrayList<>(resource.getParentFolders());
        parentFolders.add(resource.getName());
        return new ResourceDescriptor(resource.getType(), FILES_SEGMENT, parentFolders,
                resource.getBucketName(), resource.getBucketLocation(), true);
    }

    /** Synthetic descriptor under {@code .../files/} for a listed file or subfolder (relative to the version root). */
    private static ResourceDescriptor displayFileDescriptor(ResourceDescriptor resource, String relativePath, boolean isFolder) {
        List<String> segments = Arrays.asList(StringUtils.strip(relativePath, ResourceDescriptor.PATH_SEPARATOR)
                .split(ResourceDescriptor.PATH_SEPARATOR));
        List<String> parentFolders = new ArrayList<>(resource.getParentFolders());
        parentFolders.add(resource.getName());
        parentFolders.add(FILES_SEGMENT);
        parentFolders.addAll(segments.subList(0, segments.size() - 1));
        return new ResourceDescriptor(resource.getType(), segments.getLast(), parentFolders,
                resource.getBucketName(), resource.getBucketLocation(), isFolder);
    }

    private static ResourceDescriptor versionFolder(ResourceDescriptor resource, String versionId) {
        List<String> parentFolders = new ArrayList<>(resource.getParentFolders());
        parentFolders.add(resource.getName());
        parentFolders.add(VERSION_PREFIX);
        return new ResourceDescriptor(resource.getType(), versionId, parentFolders,
                resource.getBucketName(), resource.getBucketLocation(), true);
    }

    private static ResourceDescriptor versionFile(ResourceDescriptor resource, String versionId, String relativePath) {
        List<String> segments = Arrays.asList(relativePath.split(ResourceDescriptor.PATH_SEPARATOR));
        List<String> parentFolders = new ArrayList<>(resource.getParentFolders());
        parentFolders.add(resource.getName());
        parentFolders.add(VERSION_PREFIX);
        parentFolders.add(versionId);
        parentFolders.addAll(segments.subList(0, segments.size() - 1));
        String name = segments.getLast();
        return new ResourceDescriptor(resource.getType(), name, parentFolders,
                resource.getBucketName(), resource.getBucketLocation(), false);
    }

    private static String normalizePath(String filename) {
        if (StringUtils.isBlank(filename)) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Each part must have a filename (the relative path inside the resource)");
        }
        if (filename.contains("\\") || filename.startsWith(ResourceDescriptor.PATH_SEPARATOR)) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Invalid file path: " + filename);
        }

        String[] segments = filename.split(ResourceDescriptor.PATH_SEPARATOR);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new HttpException(HttpStatus.BAD_REQUEST, "Invalid file path: " + filename);
            }
        }
        if (RESERVED_SEGMENTS.contains(segments[0])) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Reserved path segment: " + segments[0]);
        }
        return filename;
    }

    private static boolean isActive(@Nullable FolderResourceMarker marker) {
        return marker != null && STATE_ACTIVE.equals(marker.getState());
    }
}
