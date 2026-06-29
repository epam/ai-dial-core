package com.epam.aidial.core.server.service.folder;

import com.epam.aidial.core.server.data.folder.FolderResourceMarker;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.blobstore.BlobStorageUtil;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
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
    private static final int PAGE_SIZE = 1000;
    // A folder or a file must not be named after a structural token.
    private static final Set<String> RESERVED_SEGMENTS = Set.of(VERSION_PREFIX, MARKER_NAME);

    private final ResourceService resourceService;

    public FolderResourceService(ResourceService resourceService) {
        this.resourceService = resourceService;
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

            EtagBuilder aggregate = new EtagBuilder();
            try {
                for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                    String relativePath = entry.getKey();
                    byte[] content = entry.getValue();
                    ResourceDescriptor file = versionFile(resource, versionId, relativePath);
                    resourceService.putFile(file, content, EtagHeader.ANY, BlobStorageUtil.getContentType(relativePath), author);
                    aggregate.append(relativePath.getBytes(StandardCharsets.UTF_8)).append(content);
                }
                String aggregateEtag = aggregate.build();

                long now = System.currentTimeMillis();
                FolderResourceMarker existing = readMarker(marker, false);
                FolderResourceMarker document = new FolderResourceMarker();
                document.setType(resource.getType().group());
                document.setSchemaVersion(SCHEMA_VERSION);
                document.setState(STATE_ACTIVE);
                document.setCurrentVersion(versionId);
                document.setEtag(aggregateEtag);
                document.setCreatedAt(existing == null ? now : existing.getCreatedAt());
                document.setUpdatedAt(now);
                document.setAuthor(author);
                document.setMetadata(handler.buildMarkerMetadata(files));

                String json = Objects.requireNonNull(ProxyUtil.convertToString(document));
                resourceService.putResource(marker, json, EtagHeader.ANY, author, false);
                return aggregateEtag;
            } catch (Exception e) {
                // Nothing is observable until the marker is committed; drop the orphan version files.
                deleteVersion(versionFolder(resource, versionId));
                throw e;
            }
        }
    }

    /**
     * Returns the {@code .dial-resource} marker of the resource, or {@code null} if it does not exist.
     */
    public FolderResourceMarker getMarker(ResourceDescriptor resource) {
        return readMarker(markerDescriptor(resource), true);
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
}
