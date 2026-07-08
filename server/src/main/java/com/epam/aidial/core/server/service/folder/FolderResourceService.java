package com.epam.aidial.core.server.service.folder;

import com.epam.aidial.core.server.data.folder.FolderResourceMarker;
import com.epam.aidial.core.server.service.InvitationService;
import com.epam.aidial.core.server.service.ShareService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.blobstore.BlobStorageUtil;
import com.epam.aidial.core.storage.data.FileMetadata;
import com.epam.aidial.core.storage.data.MetadataBase;
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
 * <p>Type-specific behavior (validation, marker metadata) is delegated to a {@link FolderResourceHandler}.
 * The engine is transport-agnostic: HTTP request/response handling lives in the controller.
 */
@Slf4j
public class FolderResourceService {

    public static final String MARKER_NAME = ".dial-resource";

    private static final String VERSION_PREFIX = "v";
    private static final int SCHEMA_VERSION = 1;
    private static final String STATE_ACTIVE = "active";
    private static final String STATE_DELETING = "deleting";
    private static final int PAGE_SIZE = 1000;
    // A folder or a file must not be named after a structural token.
    private static final Set<String> RESERVED_SEGMENTS = Set.of(VERSION_PREFIX, MARKER_NAME);

    private final ResourceService resourceService;
    private final LockService lockService;
    private final ShareService shareService;
    private final InvitationService invitationService;

    public FolderResourceService(ResourceService resourceService, LockService lockService,
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
    public String putFolder(ResourceDescriptor resource, FolderResourceHandler handler,
                            Map<String, Buffer> uploads, EtagHeader etag, String author) {
        String name = resource.getName();
        if (name == null) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Resource name is missing");
        }
        if (RESERVED_SEGMENTS.contains(name)) {
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
    public String putFile(ResourceDescriptor resource, FolderResourceHandler handler, String relativePath,
                          byte[] content, EtagHeader etag, String author) {
        String normalized = normalizePath(relativePath);
        handler.validateFileMutation(normalized, FolderResourceHandler.FileMutation.PUT);
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
    public String deleteFile(ResourceDescriptor resource, FolderResourceHandler handler, String relativePath,
                             EtagHeader etag, String author) {
        String normalized = normalizePath(relativePath);
        handler.validateFileMutation(normalized, FolderResourceHandler.FileMutation.DELETE);
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

        MutableObject<String> capturedVersion = new MutableObject<>();
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
            lockService.underBucketLock(bucketLocation, () -> {
                invitationService.cleanUpResourceLink(bucketName, bucketLocation, resource);
                shareService.revokeSharedResource(bucketName, bucketLocation, resource);
                return null;
            });
        }

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
