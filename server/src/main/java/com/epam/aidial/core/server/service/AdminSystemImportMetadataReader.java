package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.blobstore.BlobAttributes;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Map;
import java.util.zip.ZipInputStream;

/**
 * Reads the manifest and the blob metadata entries of a system import archive. Each entry is untrusted input, so it is
 * read up to 64 KiB and must be valid JSON; a metadata entry must also have no control character in a string value.
 * A failed check throws {@link AdminSystemImportRejectedException} with {@code 400}.
 */
public class AdminSystemImportMetadataReader {

    private static final int MAX_ENTRY_BYTES = 64 * 1024;

    public SystemArchiveManifest readManifest(ZipInputStream zip) throws IOException {
        byte[] bytes = readEntry(zip, "Manifest");
        try {
            return ProxyUtil.MAPPER.readValue(bytes, SystemArchiveManifest.class);
        } catch (IOException e) {
            throw rejected("Manifest is not valid JSON: " + e.getMessage());
        }
    }

    public BlobAttributes readMetadata(ZipInputStream zip) throws IOException {
        byte[] bytes = readEntry(zip, "Metadata");
        JsonNode node;
        try {
            node = ProxyUtil.MAPPER.readTree(bytes);
        } catch (IOException e) {
            throw rejected("Metadata entry is not valid JSON: " + e.getMessage());
        }
        checkNoControlChars(node);
        SystemArchiveBlobMetadata metadata;
        try {
            metadata = ProxyUtil.MAPPER.treeToValue(node, SystemArchiveBlobMetadata.class);
        } catch (IOException e) {
            throw rejected("Metadata entry is not valid JSON: " + e.getMessage());
        }
        Map<String, String> userMetadata = metadata.userMetadata() == null ? Map.of() : metadata.userMetadata();
        return new BlobAttributes(metadata.contentType(), metadata.contentEncoding(), metadata.contentDisposition(),
                metadata.contentLanguage(), metadata.cacheControl(), userMetadata);
    }

    private static byte[] readEntry(ZipInputStream zip, String kind) throws IOException {
        byte[] bytes = zip.readNBytes(MAX_ENTRY_BYTES + 1);
        if (bytes.length > MAX_ENTRY_BYTES) {
            throw rejected(kind + " entry exceeds " + MAX_ENTRY_BYTES + " bytes");
        }
        zip.closeEntry();
        return bytes;
    }

    private static void checkNoControlChars(JsonNode node) {
        if (node.isTextual()) {
            String text = node.textValue();
            for (int i = 0; i < text.length(); i++) {
                if (Character.isISOControl(text.charAt(i))) {
                    throw rejected("Metadata entry has a control character in a string value");
                }
            }
        } else if (node.isObject() || node.isArray()) {
            for (JsonNode child : node) {
                checkNoControlChars(child);
            }
        }
    }

    private static AdminSystemImportRejectedException rejected(String message) {
        return new AdminSystemImportRejectedException(HttpStatus.BAD_REQUEST, message);
    }
}
