package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.util.CoreVersion;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;

import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;

/**
 * The checks of a system import archive that do not depend on the order of its entries: the manifest and each blob
 * name. A failed check throws {@link AdminSystemImportRejectedException} with {@code 400}.
 */
public class AdminSystemImportValidationService {

    private static final int MAX_NAME_BYTES = 1024;

    private final CoreVersion coreVersion;

    public AdminSystemImportValidationService(CoreVersion coreVersion) {
        this.coreVersion = coreVersion;
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
}
