package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.util.CoreVersion;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.blobstore.BlobAttributes;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The checks of a system import archive that do not depend on the order of its entries: the manifest, each blob name
 * and each metadata entry. A failed check throws {@link AdminSystemImportRejectedException} with {@code 400}.
 */
public class AdminSystemImportValidationService {

    private static final int MAX_NAME_BYTES = 1024;
    private static final int MAX_METADATA_BYTES = 64 * 1024;

    private final CoreVersion coreVersion;

    public AdminSystemImportValidationService(CoreVersion coreVersion) {
        this.coreVersion = coreVersion;
    }

    public void validateManifest(ZipEntry first, ZipInputStream zip) throws IOException {
        if (first == null || !AdminSystemExportService.MANIFEST_ENTRY.equals(first.getName())) {
            throw rejected("Archive must start with " + AdminSystemExportService.MANIFEST_ENTRY);
        }
        Manifest manifest = readManifest(zip);
        if (manifest.formatVersion() == null || manifest.formatVersion() != AdminSystemExportService.FORMAT_VERSION) {
            throw rejected("Unsupported format version: " + manifest.formatVersion() + ", supported: "
                    + AdminSystemExportService.FORMAT_VERSION);
        }
        if (CoreVersion.parse(manifest.coreVersion()).compareTo(coreVersion) > 0) {
            throw rejected("Archive coreVersion " + manifest.coreVersion() + " is newer than the running Core " + coreVersion);
        }
    }

    public void validateBlobName(String name) {
        if (name.isEmpty()) {
            throw rejected("Empty entry name");
        }
        if (name.indexOf('\\') >= 0) {
            throw rejected("Entry name contains a backslash: " + name);
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == 0 || Character.isISOControl(c)) {
                throw rejected("Entry name contains a control character: " + name);
            }
        }
        if (name.startsWith(ResourceDescriptor.PATH_SEPARATOR)) {
            throw rejected("Entry name starts with /: " + name);
        }
        if (name.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES) {
            throw rejected("Entry name is longer than " + MAX_NAME_BYTES + " bytes: " + name);
        }

        String[] segments = name.split(ResourceDescriptor.PATH_SEPARATOR, -1);
        if (!ResourceDescriptor.PUBLIC_BUCKET.equals(segments[0]) && !ResourceDescriptor.PLATFORM_BUCKET.equals(segments[0])) {
            throw rejected("Entry name must start with " + ResourceDescriptor.PUBLIC_LOCATION + " or "
                    + ResourceDescriptor.PLATFORM_LOCATION + ": " + name);
        }
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw rejected("Entry name has an invalid path segment: " + name);
            }
        }
        checkNotExcluded(name);
    }

    public BlobAttributes readMetadata(ZipInputStream zip) throws IOException {
        byte[] bytes = zip.readNBytes(MAX_METADATA_BYTES + 1);
        if (bytes.length > MAX_METADATA_BYTES) {
            throw rejected("Metadata entry exceeds " + MAX_METADATA_BYTES + " bytes");
        }
        zip.closeEntry();

        JsonNode node;
        try {
            node = ProxyUtil.MAPPER.readTree(bytes);
        } catch (IOException e) {
            throw rejected("Metadata entry is not valid JSON: " + e.getMessage());
        }
        checkNoControlChars(node);
        SidecarMetadata metadata;
        try {
            metadata = ProxyUtil.MAPPER.treeToValue(node, SidecarMetadata.class);
        } catch (IOException e) {
            throw rejected("Metadata entry is not valid JSON: " + e.getMessage());
        }
        Map<String, String> userMetadata = metadata.userMetadata() == null ? Map.of() : metadata.userMetadata();
        return new BlobAttributes(metadata.contentType(), metadata.contentEncoding(), metadata.contentDisposition(),
                metadata.contentLanguage(), metadata.cacheControl(), userMetadata);
    }

    private Manifest readManifest(ZipInputStream zip) throws IOException {
        // capped like a sidecar: the manifest is attacker-controlled before any check has run, and a legitimate
        // one is a few dozen bytes
        byte[] bytes = zip.readNBytes(MAX_METADATA_BYTES + 1);
        if (bytes.length > MAX_METADATA_BYTES) {
            throw rejected("Manifest entry exceeds " + MAX_METADATA_BYTES + " bytes");
        }
        zip.closeEntry();
        try {
            return ProxyUtil.MAPPER.readValue(bytes, Manifest.class);
        } catch (IOException e) {
            throw rejected("Manifest is not valid JSON: " + e.getMessage());
        }
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

    private static void checkNotExcluded(String name) {
        int firstSlash = name.indexOf('/');
        if (firstSlash < 0) {
            return;
        }
        String remainder = name.substring(firstSlash + 1);
        for (String group : AdminSystemExportService.EXCLUDED_GROUPS) {
            if (remainder.regionMatches(true, 0, group, 0, group.length())) {
                throw rejected("Entry under an excluded group: " + name);
            }
        }
    }

    private static AdminSystemImportRejectedException rejected(String message) {
        return new AdminSystemImportRejectedException(HttpStatus.BAD_REQUEST, message);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Manifest(Integer formatVersion, String coreVersion) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SidecarMetadata(String contentType, String contentEncoding, String contentDisposition,
                                   String contentLanguage, String cacheControl, Map<String, String> userMetadata) {
    }
}
