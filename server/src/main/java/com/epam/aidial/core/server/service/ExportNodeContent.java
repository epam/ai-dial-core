package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.data.ExportResourceIdentity;
import com.epam.aidial.core.server.data.folder.FolderResourceMarker;
import com.epam.aidial.core.server.service.ExportArchive.BodyRedactor;
import com.epam.aidial.core.server.service.ExportTypePolicy.Content;
import com.epam.aidial.core.server.util.HashUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import javax.annotation.Nullable;

/**
 * What the export keeps of a resource after it has been read. There are three kinds of content:
 * <ul>
 *   <li>{@link Json}: a JSON resource, read once. It keeps the redacted bytes and their {@code sha256}, and the archive writes exactly
 *   those bytes, so the archive holds what the registry and the graph were built from. The stored body itself is not kept.</li>
 *   <li>{@link StreamedFile}: a File, copied from storage as it is stored when the archive is written.</li>
 *   <li>{@link Complex}: a Skill, whose files of the pinned version are copied as they are stored when the archive is written.</li>
 * </ul>
 */
public sealed interface ExportNodeContent {

    /**
     * @return the identity of the resource, with the digest of the {@code $id} for a schema
     */
    ExportResourceIdentity identity();

    /**
     * @return whether the bytes of the resource are copied from storage as they are stored and never read as JSON, which holds for a File
     *         and for a Skill
     */
    boolean isCopiedAsStored();


    /**
     * A JSON resource, read once.
     *
     * @param bytes     the redacted bytes that the archive writes; {@code null} when no redaction policy is configured, and then no archive
     *                  can be written from the closure
     * @param sha256    the digest of {@code bytes}; {@code null} exactly when {@code bytes} is
     * @param createdAt the creation time of the stored resource in epoch milliseconds, when it is known
     * @param updatedAt the time of its last write in epoch milliseconds, when it is known
     */
    record Json(ExportResourceIdentity identity, @Nullable byte[] bytes, @Nullable String sha256, @Nullable Long createdAt,
                @Nullable Long updatedAt) implements ExportNodeContent {

        @Override
        public boolean isCopiedAsStored() {
            return false;
        }
    }

    /**
     * What the plan knows of a File that is copied as it is stored: no byte of its content. The copy compares them with what it reads.
     *
     * @param length    the stored length in bytes
     * @param etag      the etag when the closure was planned
     * @param createdAt the creation time of the stored file in epoch milliseconds, when it is known
     * @param updatedAt the time of its last write in epoch milliseconds, when it is known
     */
    record StreamedFile(ExportResourceIdentity identity, long length, String etag, @Nullable String contentType, @Nullable Long createdAt,
                        @Nullable Long updatedAt) implements ExportNodeContent {

        @Override
        public boolean isCopiedAsStored() {
            return true;
        }
    }

    /**
     * What the plan knows of a Skill: the version that it pinned, the aggregate etag of its marker, and its files in the order of their
     * relative paths. No byte of any file is read by the plan.
     *
     * @param versionId  the current version when the plan read the marker; the files of a version are immutable
     * @param markerEtag the aggregate etag of the marker at that time
     */
    record Complex(ExportResourceIdentity identity, String versionId, String markerEtag, List<ComplexFile> files)
            implements ExportNodeContent {

        @Override
        public boolean isCopiedAsStored() {
            return true;
        }

    }

    /**
     * @param relativePath the path of the file inside the Skill, as stored and already checked to hold no empty, {@code .} or {@code ..} segment
     */
    record ComplexFile(String relativePath, long length, String etag) {
    }

    /**
     * Redacts a body that was read. A body that the redactor refuses ends the plan with the refusal, which is before the first byte.
     *
     * @param redactor  the redaction policy, or {@code null} when none is configured; then the node only has its identity
     * @param body      the stored body as it was read
     * @param createdAt the creation time of the stored resource, when it is known
     * @param updatedAt the time of its last write, when it is known
     */
    static Json of(@Nullable BodyRedactor redactor, ResourceDescriptor descriptor, String body, @Nullable Long createdAt, @Nullable Long updatedAt) {
        // the body is parsed once, and only when something needs the tree: the identity of a schema, or the redactor
        JsonNode tree = redactor != null || isSchema(descriptor) ? ProxyUtil.parseTree(body) : null;
        ExportResourceIdentity identity = identityOf(descriptor, tree);
        if (redactor == null) {
            return new Json(identity, null, null, createdAt, updatedAt);
        }
        // a body that cannot be parsed goes to the text entry, which refuses it
        byte[] bytes = tree != null ? redactor.redact(identity, body, tree) : redactor.redact(identity, body);
        return new Json(identity, bytes, HashUtil.sha256Hex(bytes), createdAt, updatedAt);
    }

    /**
     * The node of an entity of the file configuration: it has a tree and no text, and the tree goes to the redactor as it is. It has no
     * times.
     */
    static Json of(@Nullable BodyRedactor redactor, ResourceDescriptor descriptor, JsonNode tree) {
        ExportResourceIdentity identity = identityOf(descriptor, tree);
        if (redactor == null) {
            return new Json(identity, null, null, null, null);
        }
        byte[] bytes = redactor.redact(identity, tree);
        return new Json(identity, bytes, HashUtil.sha256Hex(bytes), null, null);
    }

    /**
     * The node of a File: its identity and its stored length and etag, and no content. The content is never read here, so it cannot be
     * redacted, parsed or examined.
     */
    static StreamedFile ofStreamedFile(ResourceDescriptor descriptor, long length, String etag, @Nullable String contentType,
                                       @Nullable Long createdAt, @Nullable Long updatedAt) {
        return new StreamedFile(ExportResourceIdentity.ofDescriptor(descriptor, null), length, etag, contentType, createdAt, updatedAt);
    }

    /**
     * The node of a Skill: its identity, the pinned version and its files with their stored lengths and etags, taken from the marker
     * alone, so no file is read.
     */
    static Complex ofComplex(ResourceDescriptor descriptor, FolderResourceMarker marker) {
        List<ComplexFile> files = new ArrayList<>();
        new TreeMap<>(marker.getFileMetadata()).forEach((path, metadata) -> files.add(new ComplexFile(path, metadata.getSize(), metadata.getEtag())));
        return new Complex(ExportResourceIdentity.ofDescriptor(descriptor, null), marker.getCurrentVersion(), marker.getEtag(), List.copyOf(files));
    }

    private static boolean isSchema(ResourceDescriptor descriptor) {
        return ExportTypePolicy.contentOf(ExportTypePolicy.typeOf(descriptor)) == Content.SCHEMA;
    }

    private static ExportResourceIdentity identityOf(ResourceDescriptor descriptor, @Nullable JsonNode tree) {
        return ExportResourceIdentity.ofDescriptor(descriptor, isSchema(descriptor) && tree != null ? MergedConfigStore.extractSchemaId(tree) : null);
    }
}
