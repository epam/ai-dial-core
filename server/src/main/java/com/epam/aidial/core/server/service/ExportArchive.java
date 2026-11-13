package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.data.folder.FolderResourceMarker;
import com.epam.aidial.core.server.service.resource.ComplexResourceService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;

/**
 * The writer of the archive of an export, which {@link ExportStreamer} drives one resource at a time. Order: the technical manifest, then
 * one entry per resource at its storage path.
 *
 * <p>Not thread-safe; serves one export. It does not close the stream; {@link #discard} must be called when the export ends,
 * successfully or not.
 */
@Slf4j
public final class ExportArchive {

    public static final String MANIFEST_ENTRY = ".dial-export/manifest.json";
    /** The version of the layout; 1 is the System Export, so a reader of that export refuses this archive. */
    public static final int FORMAT_VERSION = 2;
    public static final String MODE = "selective";

    /** Every file goes through it, so the memory of a copy does not depend on the size of the file. */
    private static final int COPY_BUFFER_SIZE = 32 * 1024;

    private record TechnicalManifest(int formatVersion, String coreVersion, String createdAt, String mode) {
    }

    private final DiscardableZipOutputStream zip;
    private final ResourceService resourceService;
    private final ComplexResourceService complexResourceService;

    public ExportArchive(OutputStream out, ResourceService resourceService, ComplexResourceService complexResourceService) {
        this.zip = new DiscardableZipOutputStream(out);
        this.resourceService = resourceService;
        this.complexResourceService = complexResourceService;
    }

    public void writeManifest(String coreVersion, Instant createdAt) throws IOException {
        writeJson(MANIFEST_ENTRY, ProxyUtil.MAPPER.writeValueAsBytes(
                new TechnicalManifest(FORMAT_VERSION, coreVersion, createdAt.truncatedTo(ChronoUnit.SECONDS).toString(), MODE)));
    }

    public void writeJson(String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    /**
     * Copies a File in one pass through a fixed buffer and checks that exactly the length at open was copied. An etag that is not
     * {@code expectedEtag} or that changed during the copy is one WARN record and not a failure. A file that is gone, a stream of the wrong
     * length or a failing read fails the export. A message names the key, never a value.
     */
    public void copyFile(ResourceDescriptor descriptor, String name, String expectedEtag) throws IOException {
        String key = ExportResourceNames.buildKey(descriptor);
        try (ResourceService.ResourceStream stored = resourceService.getResourceStream(descriptor, EtagHeader.ANY)) {
            if (stored == null) {
                throw new IllegalStateException("A resource of the export no longer exists (resource=" + key + ")");
            }
            copyInto(name, key, stored);
            ResourceItemMetadata current = resourceService.getResourceMetadata(descriptor);
            String after = current == null ? null : current.getEtag();
            if (after == null) {
                throw new IllegalStateException("A resource of the export no longer exists (resource=" + key + ")");
            }
            if (!stored.etag().equals(expectedEtag) || !after.equals(stored.etag())) {
                warnChanged(key, expectedEtag, after);
            }
        }
    }

    /**
     * Copies a Skill: each file of the version that the marker pins, in the order of the relative paths, below {@code <name>/}. A file that
     * is gone or has another length than the marker listed fails the export; a marker that changed or is gone afterwards is one WARN
     * record, because the version that was copied is whole.
     *
     * @throws IllegalStateException if a stored path has an empty, {@code .} or {@code ..} segment; nothing of the Skill is written then
     */
    public void copySkill(ResourceDescriptor descriptor, String name, FolderResourceMarker marker) throws IOException {
        String key = ExportResourceNames.buildKey(descriptor);
        Map<String, FolderResourceMarker.ResourceFileMetadata> files = new TreeMap<>(marker.getFileMetadata());
        // every stored path is checked before the first file of the Skill is written
        files.keySet().forEach(relativePath -> ExportResourceNames.requireSafeRelativePath(relativePath, key));
        for (Map.Entry<String, FolderResourceMarker.ResourceFileMetadata> file : files.entrySet()) {
            try (ResourceService.ResourceStream stored = complexResourceService.getVersionFileStream(descriptor, marker.getCurrentVersion(), file.getKey())) {
                if (stored == null) {
                    throw new IllegalStateException("A resource of the export no longer exists (resource=" + key + ")");
                }
                if (stored.contentLength() != file.getValue().getSize()) {
                    throw new IllegalStateException("A resource of the export changed while it was exported (resource=" + key + ")");
                }
                copyInto(name + "/" + file.getKey(), key, stored);
            }
        }
        FolderResourceMarker current = complexResourceService.getMarker(descriptor);
        String after = current == null ? null : current.getEtag();
        if (!marker.getEtag().equals(after)) {
            warnChanged(key, marker.getEtag(), after);
        }
    }

    public void finish() throws IOException {
        zip.finish();
        zip.flush();
    }

    /** Ends the use of the stream, so a failed export leaves an archive without the central directory. */
    public void discard() {
        zip.discard();
    }

    private static void warnChanged(String key, String etagBefore, String etagAfter) {
        log.warn("Export: a resource changed while it was copied and was written whole: resource={} etagBefore={} etagAfter={}", key,
                etagBefore, etagAfter);
    }

    /**
     * @throws IllegalStateException if the number of bytes copied is not the length of the stream
     */
    private void copyInto(String name, String key, ResourceService.ResourceStream stored) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long copied = 0;
        for (int read = stored.inputStream().read(buffer); read != -1; read = stored.inputStream().read(buffer)) {
            zip.write(buffer, 0, read);
            copied += read;
        }
        zip.closeEntry();
        if (copied != stored.contentLength()) {
            throw new IllegalStateException("A file of the export was not copied completely (resource=" + key + ")");
        }
    }
}
