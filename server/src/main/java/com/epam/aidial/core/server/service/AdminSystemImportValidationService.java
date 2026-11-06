package com.epam.aidial.core.server.service;

import com.epam.aidial.core.credentials.exception.CekEncryptionException;
import com.epam.aidial.core.credentials.keymanagement.KeyManagementService;
import com.epam.aidial.core.server.util.CoreVersion;
import com.epam.aidial.core.storage.blobstore.BlobAttributes;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;

/**
 * The checks of a system import archive that do not depend on the order of its entries: the manifest, each blob name
 * and each encryption key. A failed check throws {@link AdminSystemImportRejectedException} with {@code 400}.
 */
public class AdminSystemImportValidationService {

    private static final int MAX_NAME_BYTES = 1024;
    private static final int MAX_ENCRYPTION_KEY_BYTES = 64 * 1024;
    private static final String IDENTITY_ENCODING = "identity";
    private static final String GZIP_ENCODING = "gzip";
    private static final String CREDENTIALS_GROUP = ResourceTypes.CREDENTIALS.group() + ResourceDescriptor.PATH_SEPARATOR;
    private static final String ENCRYPTION_KEYS_GROUP = ResourceTypes.ENCRYPTION_KEYS.group() + ResourceDescriptor.PATH_SEPARATOR;

    private final CoreVersion coreVersion;
    private final KeyManagementService keyManagementService;
    private final List<String> excludedGroups;

    /**
     * @param kmsEnabled whether a KMS provider wraps the content encryption keys: the archive may then hold them, and
     *                   each is unwrapped with {@code keyManagementService}; otherwise they are rejected like credentials
     */
    public AdminSystemImportValidationService(CoreVersion coreVersion, boolean kmsEnabled, KeyManagementService keyManagementService) {
        this.coreVersion = coreVersion;
        this.keyManagementService = keyManagementService;
        this.excludedGroups = kmsEnabled ? List.of(CREDENTIALS_GROUP) : List.of(CREDENTIALS_GROUP, ENCRYPTION_KEYS_GROUP);
    }

    public void validateManifestEntry(ZipEntry first) {
        if (first == null || !SystemArchiveManifest.ENTRY.equals(first.getName())) {
            throw rejected("Archive must start with " + SystemArchiveManifest.ENTRY);
        }
    }

    public void validateManifest(SystemArchiveManifest manifest) {
        if (manifest.formatVersion() == null || manifest.formatVersion() != SystemArchiveManifest.FORMAT_VERSION) {
            throw rejected("Unsupported format version: " + manifest.formatVersion() + ", supported: "
                    + SystemArchiveManifest.FORMAT_VERSION);
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

    public boolean isEncryptionKey(String name) {
        return startsWithGroup(name, ENCRYPTION_KEYS_GROUP);
    }

    public void validateEncryptionKey(String path, BlobAttributes attributes, byte[] content) {
        byte[] wrapped = decode(path, attributes.contentEncoding(), content);
        try {
            keyManagementService.decrypt(wrapped);
        } catch (CekEncryptionException e) {
            throw rejected("Encryption key cannot be decrypted with the KMS key of this environment: " + path);
        }
    }

    private static byte[] decode(String path, String contentEncoding, byte[] content) {
        checkKeySize(path, content);
        if (StringUtils.isBlank(contentEncoding) || IDENTITY_ENCODING.equalsIgnoreCase(contentEncoding.trim())) {
            return content;
        }
        if (!GZIP_ENCODING.equalsIgnoreCase(contentEncoding.trim())) {
            throw rejected("Encryption key has an unsupported content encoding: " + path);
        }
        byte[] decoded;
        try (InputStream gzip = new GZIPInputStream(new ByteArrayInputStream(content))) {
            decoded = gzip.readNBytes(MAX_ENCRYPTION_KEY_BYTES + 1);
        } catch (IOException e) {
            throw rejected("Encryption key is not valid gzip: " + path);
        }
        checkKeySize(path, decoded);
        return decoded;
    }

    private static void checkKeySize(String path, byte[] key) {
        if (key.length == 0 || key.length > MAX_ENCRYPTION_KEY_BYTES) {
            throw rejected("Encryption key is not a wrapped key: " + path);
        }
    }

    private void checkNotExcluded(String name) {
        for (String group : excludedGroups) {
            if (startsWithGroup(name, group)) {
                throw rejected("Entry under an excluded group: " + name);
            }
        }
    }

    private static boolean startsWithGroup(String name, String group) {
        int firstSlash = name.indexOf(ResourceDescriptor.PATH_SEPARATOR);
        return firstSlash >= 0 && name.regionMatches(true, firstSlash + 1, group, 0, group.length());
    }

    private static AdminSystemImportRejectedException rejected(String message) {
        return new AdminSystemImportRejectedException(HttpStatus.BAD_REQUEST, message);
    }
}
