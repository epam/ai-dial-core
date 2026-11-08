package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.data.ExportResourceIdentity;
import com.epam.aidial.core.server.service.ExportClosureWalker.Closure;
import com.epam.aidial.core.server.service.ExportClosureWalker.ClosureNode;
import com.epam.aidial.core.server.service.ExportNodeContent.Complex;
import com.epam.aidial.core.server.service.ExportNodeContent.Json;
import com.epam.aidial.core.server.service.ExportNodeContent.StreamedFile;
import com.epam.aidial.core.server.service.ExportRegistryBuilder.Packaged;
import com.epam.aidial.core.server.util.HashUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.annotation.Nullable;

/**
 * The archive of an export, built from a closure.
 *
 * <p>{@link #prepare} takes the closure, whose JSON nodes already hold their redacted bytes, builds the registry from it and checks every
 * entry name before any byte is written. {@link #writeTo} writes the technical manifest and the registry first, then each resource in
 * the order of the closure: a JSON resource as the redacted bytes that were kept, a File or a Skill copied from storage through a
 * {@link StreamSource}. Last comes the verification entry with the {@code sha256} of every other entry. The entry names come from the
 * identities of the resources, and the entries carry a fixed time, so two exports of the same state are byte for byte the same apart
 * from the technical manifest's {@code createdAt}.
 */
@Slf4j
public final class ExportArchive {

    /** The technical manifest, the entry of the System Export. */
    public static final String MANIFEST_ENTRY = AdminSystemExportService.MANIFEST_ENTRY;
    public static final String REGISTRY_ENTRY = ".dial-export/dependency-registry.json";
    /** The version of the layout; 1 is the System Export, so a reader of that export refuses this archive. */
    public static final int FORMAT_VERSION = 2;
    public static final String MODE = "selective";
    public static final String VERIFICATION_ENTRY = ".dial-export/verification.json";

    /** 1980-01-01, the earliest time that the ZIP format holds. */
    private static final long ENTRY_TIME_MILLIS = 315_532_800_000L;

    /**
     * Turns the stored body of a resource into the bytes that go into the archive, removing what must not be exported. An
     * implementation works on the stored JSON, never on a decrypted or in-memory object, and fails by throwing: a body that
     * cannot be redacted is never written.
     */
    public interface BodyRedactor {

        /**
         * @param storedBody the body as it was read
         */
        byte[] redact(ExportResourceIdentity identity, String storedBody);

        /**
         * The same as {@link #redact(ExportResourceIdentity, String)} for a body that the caller has already parsed, so that a caller
         * that needed the tree (for the identity of a schema) does not make the redactor parse it again. {@code parsedBody} is the
         * parse of {@code storedBody}. The default ignores the tree, so a redactor that only handles text behaves exactly as before and
         * the body is never written to text again; the production redactor overrides it. The tree is not changed.
         */
        default byte[] redact(ExportResourceIdentity identity, String storedBody, JsonNode parsedBody) {
            return redact(identity, storedBody);
        }

        /**
         * The same policy for an entity that has no stored text, the representation of an entity of the file configuration. The default
         * writes the tree to text, which only a redactor that handles text uses; the production redactor overrides it. The tree is not changed.
         */
        default byte[] redact(ExportResourceIdentity identity, JsonNode tree) {
            return redact(identity, tree.toString());
        }
    }

    /** Opens the bytes of Files and Skills when the archive is written. A closure without a File or a Skill needs none of it. */
    public interface StreamSource {

        /**
         * Opens the stored bytes of a File. The caller closes the stream. The default has no stream, so a closure with a File cannot be
         * written through a source that does not provide one.
         *
         * @return the open stream, or {@code null} if the file no longer exists
         */
        @Nullable
        default StoredStream openStream(ResourceDescriptor descriptor) throws IOException {
            return null;
        }

        /**
         * @return the current etag of a File, or {@code null} if it no longer exists
         */
        @Nullable
        default String readEtag(ResourceDescriptor descriptor) {
            return null;
        }

        /**
         * Opens a file of the pinned version of a Skill; the files of a version are immutable. The caller closes the stream.
         *
         * @return the open stream, or {@code null} if the file no longer exists in that version
         */
        @Nullable
        default StoredStream openVersionFile(ResourceDescriptor skill, String versionId, String relativePath) throws IOException {
            return null;
        }

        /**
         * @return the current aggregate etag of the marker of a Skill, or {@code null} if the Skill no longer exists
         */
        @Nullable
        default String readMarkerEtag(ResourceDescriptor skill) {
            return null;
        }
    }

    /**
     * The stored bytes of a File as they are opened, with the etag and the length that go with them.
     *
     * @param etag   the etag of the stored file when it was opened
     * @param length the length of the stored file in bytes when it was opened
     */
    public record StoredStream(InputStream content, String etag, long length, @Nullable String contentType) implements Closeable {

        @Override
        public void close() throws IOException {
            content.close();
        }
    }

    /** The technical manifest: what the System Export holds, and the mode that marks this archive as selective. */
    private record TechnicalManifest(int formatVersion, String coreVersion, String createdAt, String mode) {
    }

    /**
     * The sidecar of a resource: the bytes that follow it are redacted JSON, so it claims nothing of the stored blob. The dates are those
     * of the stored resource, in the format of the System Export, and are absent when they are not known.
     */
    private static byte[] sidecarOf(Json content) {
        return sidecarOf("application/json", content.createdAt(), content.updatedAt());
    }

    private static byte[] sidecarOf(@Nullable String contentType, @Nullable Long createdAt, @Nullable Long updatedAt) {
        return toJson(new AdminSystemExportService.BlobMetadata(contentType, null, null, null, null,
                isoOf(createdAt), isoOf(updatedAt), null), "The sidecar");
    }

    @Nullable
    private static String isoOf(@Nullable Long epochMillis) {
        return epochMillis == null ? null : Instant.ofEpochMilli(epochMillis).toString();
    }

    private record Entry(String name, ExportNodeContent content, ResourceDescriptor descriptor) {
    }

    private final ExportRegistry registry;
    private final byte[] technicalManifest;
    private final List<Entry> entries;
    @Nullable
    private final StreamSource streams;

    private ExportArchive(ExportRegistry registry, byte[] technicalManifest, List<Entry> entries, @Nullable StreamSource streams) {
        this.registry = registry;
        this.technicalManifest = technicalManifest;
        this.entries = entries;
        this.streams = streams;
    }

    /**
     * @param streams reads the bytes of the Files and Skills of the closure when the archive is written; {@code null} when the closure has none
     * @throws IllegalStateException if two resources have one entry name, a JSON node of the closure has no bytes because no redactor was set
     *                               when it was read, or a File or a Skill is in the closure and there is no {@code streams}; nothing has been
     *                               written
     * @throws com.epam.aidial.core.storage.http.HttpException 504 if the deadline passes while the entries are listed
     */
    public static ExportArchive prepare(Closure closure, @Nullable StreamSource streams, String coreVersion, Instant createdAt,
                                        ExportLimits.Deadline deadline) {
        Map<ResourceDescriptor, Packaged> packaged = new LinkedHashMap<>();
        List<Entry> entries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (ClosureNode node : closure.nodes()) {
            deadline.check();
            ExportNodeContent content = node.content();
            if (!(content instanceof Json) && streams == null) {
                throw new IllegalStateException("A resource of an export is copied from storage and no source of its bytes was given");
            }
            ExportResourceIdentity identity = content.identity();
            // a path with an empty, . or .. segment is refused here, before the first byte, whatever the origin
            identity.key();
            String entryName = ExportFileEntities.isFile(node.descriptor()) ? identity.fragmentName() : node.descriptor().getAbsoluteFilePath();
            if (!names.add(entryName)) {
                throw new IllegalStateException("Two resources of an export have the same entry name");
            }
            if (content instanceof Json json && json.bytes() == null) {
                throw new IllegalStateException("A resource of an export was read without a redaction policy");
            }
            if (content instanceof Complex complex) {
                // the files of a Skill: every stored path is checked here, before the first byte, and every file has its own entry name
                for (ExportNodeContent.ComplexFile file : complex.files()) {
                    ExportResourceIdentity.requireSafeRelativePath(file.relativePath());
                    if (!names.add(entryName + "/" + file.relativePath())) {
                        throw new IllegalStateException("Two resources of an export have the same entry name");
                    }
                }
            }
            entries.add(new Entry(entryName, content, node.descriptor()));
            packaged.put(node.descriptor(), new Packaged(identity, entryName));
        }
        TechnicalManifest technical = new TechnicalManifest(FORMAT_VERSION, coreVersion, createdAt.truncatedTo(ChronoUnit.SECONDS).toString(), MODE);
        return new ExportArchive(ExportRegistryBuilder.build(closure, packaged), toJson(technical, "The technical manifest"),
                List.copyOf(entries), streams);
    }

    public ExportRegistry registry() {
        return registry;
    }

    /**
     * @throws com.epam.aidial.core.storage.http.HttpException 504 if the deadline passes between two entries; the archive is
     *                                                          then left unfinished, as for any failure while streaming
     */
    public void writeTo(OutputStream out, ExportLimits.Deadline deadline) throws IOException {
        byte[] registryBytes = toJson(registry, "The registry");
        DiscardableZipOutputStream zip = new DiscardableZipOutputStream(out);
        try {
            List<Map<String, Object>> checksums = new ArrayList<>();
            List<Map<String, String>> changes = new ArrayList<>();
            writeEntry(zip, MANIFEST_ENTRY, technicalManifest);
            checksums.add(checksum(MANIFEST_ENTRY, technicalManifest));
            writeEntry(zip, REGISTRY_ENTRY, registryBytes);
            checksums.add(checksum(REGISTRY_ENTRY, registryBytes));
            for (Entry entry : entries) {
                deadline.check();
                switch (entry.content()) {
                    case StreamedFile file -> writeStreamedFile(zip, entry, file, checksums, changes, deadline);
                    case Complex complex -> writeComplex(zip, entry, complex, checksums, changes, deadline);
                    case Json json -> writeJson(zip, entry, json, checksums);
                }
            }
            writeEntry(zip, VERIFICATION_ENTRY, verificationJson(checksums, changes));
            zip.finish();
            zip.flush();
        } finally {
            zip.discard();
        }
    }

    private void writeJson(ZipOutputStream zip, Entry entry, Json json, List<Map<String, Object>> checksums) throws IOException {
        if (!ExportFileEntities.isFile(entry.descriptor())) {
            // a resource with a blob is preceded by its sidecar, as in the System Export
            String sidecarName = AdminSystemExportService.METADATA_ENTRY_PREFIX + entry.name();
            byte[] sidecarBytes = sidecarOf(json);
            writeEntry(zip, sidecarName, sidecarBytes);
            checksums.add(checksum(sidecarName, sidecarBytes));
        }
        writeEntry(zip, entry.name(), json.bytes());
        checksums.add(checksum(entry.name(), json.sha256()));
    }

    /** The size of the buffer through which every file goes; the memory of a copy does not depend on the size of the file. */
    private static final int COPY_BUFFER_SIZE = 32 * 1024;

    /**
     * Copies a File: copies the stored bytes into the entry through a fixed buffer, in one pass, computing the
     * {@code sha256} on the way, and checks that exactly the length at open was copied. The etag is read again after the last byte: an etag that
     * is not the one of the plan, or that changed during the copy, is listed as {@code changed-during-export} with both values, and a newer
     * complete version is not a failure. A file that is gone, a stream that ends early or is longer than its length, or a read that fails, fails
     * the export. The content is never parsed, redacted, converted or examined, and no message names a path or a value.
     */
    private void writeStreamedFile(ZipOutputStream zip, Entry entry, StreamedFile expected, List<Map<String, Object>> checksums,
                                   List<Map<String, String>> changes, ExportLimits.Deadline deadline) throws IOException {
        try (StoredStream stored = streams.openStream(entry.descriptor())) {
            if (stored == null) {
                throw new IllegalStateException("A resource of the export no longer exists");
            }
            copyInto(zip, entry.name(), stored, expected.createdAt(), expected.updatedAt(), checksums, deadline);
            String after = streams.readEtag(entry.descriptor());
            if (after == null) {
                throw new IllegalStateException("A resource of the export no longer exists");
            }
            if (!stored.etag().equals(expected.etag()) || !after.equals(stored.etag())) {
                changes.add(change(entry.name(), expected.etag(), after));
            }
        }
    }

    /**
     * Copies a Skill: copies each file of the version that the plan pinned, in the order of the relative paths, as
     * a File is copied, below the logical folder {@code <entry name of the Skill>/}, without the marker and the version folder. The files of a
     * version are immutable, so a file that is gone or has another length than the marker listed fails the export. After the last file the marker
     * is read again: another aggregate etag, or a Skill that is gone, is listed as {@code changed-during-export} and does not fail the export,
     * because the version that was copied is whole. No file is examined for references.
     */
    private void writeComplex(ZipOutputStream zip, Entry entry, Complex expected, List<Map<String, Object>> checksums,
                              List<Map<String, String>> changes, ExportLimits.Deadline deadline) throws IOException {
        for (ExportNodeContent.ComplexFile file : expected.files()) {
            deadline.check();
            try (StoredStream stored = streams.openVersionFile(entry.descriptor(), expected.versionId(), file.relativePath())) {
                if (stored == null) {
                    throw new IllegalStateException("A resource of the export no longer exists");
                }
                if (stored.length() != file.length()) {
                    throw new IllegalStateException("A resource of the export changed while it was exported");
                }
                copyInto(zip, entry.name() + "/" + file.relativePath(), stored, null, null, checksums, deadline);
            }
        }
        String after = streams.readMarkerEtag(entry.descriptor());
        if (!expected.markerEtag().equals(after)) {
            changes.add(change(entry.name(), expected.markerEtag(), after));
        }
    }

    private static Map<String, String> change(String name, String etagBefore, @Nullable String etagAfter) {
        Map<String, String> change = new LinkedHashMap<>();
        change.put("name", name);
        change.put("etagBefore", etagBefore);
        change.put("etagAfter", etagAfter);
        return change;
    }

    /**
     * Writes the sidecar and then the entry, copying the stream through the fixed buffer and computing its {@code sha256}, and records the entry in
     * the verification list with its length and etag.
     *
     * @throws IllegalStateException if the number of bytes copied is not the length of the stream
     */
    private void copyInto(ZipOutputStream zip, String name, StoredStream stored, @Nullable Long createdAt, @Nullable Long updatedAt,
                          List<Map<String, Object>> checksums, ExportLimits.Deadline deadline) throws IOException {
        String sidecarName = AdminSystemExportService.METADATA_ENTRY_PREFIX + name;
        byte[] sidecarBytes = sidecarOf(stored.contentType(), createdAt, updatedAt);
        writeEntry(zip, sidecarName, sidecarBytes);
        checksums.add(checksum(sidecarName, sidecarBytes));

        MessageDigest digest = newDigest();
        ZipEntry zipEntry = new ZipEntry(name);
        zipEntry.setTime(ENTRY_TIME_MILLIS);
        zip.putNextEntry(zipEntry);
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long copied = 0;
        for (int read = stored.content().read(buffer); read != -1; read = stored.content().read(buffer)) {
            deadline.check();
            zip.write(buffer, 0, read);
            digest.update(buffer, 0, read);
            copied += read;
        }
        zip.closeEntry();
        if (copied != stored.length()) {
            throw new IllegalStateException("A file of the export was not copied completely");
        }
        Map<String, Object> checksum = new LinkedHashMap<>();
        checksum.put("name", name);
        checksum.put("sha256", HexFormat.of().formatHex(digest.digest()));
        checksum.put("length", copied);
        checksum.put("etag", stored.etag());
        checksums.add(checksum);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(ENTRY_TIME_MILLIS);
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    private static Map<String, Object> checksum(String name, byte[] content) {
        return checksum(name, HashUtil.sha256Hex(content));
    }

    private static Map<String, Object> checksum(String name, String sha256) {
        Map<String, Object> checksum = new LinkedHashMap<>();
        checksum.put("name", name);
        checksum.put("sha256", sha256);
        return checksum;
    }

    /**
     * @return the JSON of a metadata entry; the order of the fields is the order of the declarations
     */
    private static byte[] toJson(Object value, String what) {
        try {
            return ProxyUtil.MAPPER.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(what + " cannot be serialized", e);
        }
    }

    private static byte[] verificationJson(List<Map<String, Object>> checksums, List<Map<String, String>> changes) {
        Map<String, Object> verification = new LinkedHashMap<>();
        verification.put("entries", checksums);
        if (!changes.isEmpty()) {
            verification.put("changedDuringExport", changes);
        }
        return toJson(verification, "The verification entry");
    }
}
