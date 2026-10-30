package com.epam.aidial.core.storage.service;

import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import lombok.extern.slf4j.Slf4j;
import org.jclouds.blobstore.domain.PageSet;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.domain.StorageType;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;

/**
 * Deletes blobs under a staging folder once they are old enough, and deletes one staging prefix outright.
 *
 * <p>Uploads are grouped under {@code <folder>/<timestamp>/<id>}, where {@code <timestamp>} is formatted with
 * {@link #FOLDER_FORMATTER} (e.g. {@code .dial-tmp/2024-01-31 13:34:10/<uuid>}). Because these timestamp folders
 * sort lexicographically the same as chronologically, {@link #cleanup()} can stop at the first one that is not
 * expired yet. Shared by {@code ResourceService}'s {@code .dial-tmp} sweep (one-hour TTL) and the system import
 * staging sweep (24-hour TTL, a different folder and instance).
 */
@Slf4j
public class TempFolderCleaner {

    private static final int PAGE_SIZE = 1000;

    /**
     * The timestamp format staging folders are named with, e.g. {@code 2024-01-31 13:34:10}. Public so a caller
     * that builds a new staging folder (a system import) can use the same format.
     */
    public static final DateTimeFormatter FOLDER_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .toFormatter();

    private final BlobStorage blobStorage;
    private final String folder;
    private final Duration ttl;

    public TempFolderCleaner(BlobStorage blobStorage, String folder, Duration ttl) {
        this.blobStorage = blobStorage;
        this.folder = folder;
        this.ttl = ttl;
    }

    /**
     * Scans {@link #folder} and removes every blob whose parent timestamp folder ended more than {@link #ttl} ago.
     * Folders sort by time, so the scan stops at the first folder that is not expired yet. Never throws: a
     * failure, including an unparseable folder name, is logged and the scan stops there, same as before extraction.
     */
    public void cleanup() {
        log.debug("Cleaning up temp folder: {}", folder);
        try {
            LocalDateTime now = LocalDateTime.now();
            String token = null;
            exit: do {
                PageSet<? extends StorageMetadata> set = blobStorage.list(folder, token, PAGE_SIZE, true);
                for (StorageMetadata meta : set) {
                    if (meta.getType() != StorageType.BLOB) {
                        continue;
                    }
                    String path = meta.getName();
                    if (isExpired(path, now)) {
                        try {
                            blobStorage.delete(path);
                        } catch (Throwable e) {
                            log.warn("Failed to delete expired temp file: {}", path, e);
                        }
                    } else {
                        break exit;
                    }
                }
                token = set.getNextMarker();
            } while (token != null);
        } catch (Throwable e) {
            log.warn("Failed to clean up temp folder: {}", folder, e);
        }
    }

    /**
     * Deletes every blob under {@code prefix}, regardless of age. A per-blob failure is logged and does not stop
     * the rest of the deletion.
     */
    public void deleteFolder(String prefix) {
        String token = null;
        do {
            PageSet<? extends StorageMetadata> set = blobStorage.list(prefix, token, PAGE_SIZE, true);
            for (StorageMetadata meta : set) {
                if (meta.getType() == StorageType.BLOB) {
                    try {
                        blobStorage.delete(meta.getName());
                    } catch (Throwable e) {
                        log.warn("Failed to delete staged blob: {}", meta.getName(), e);
                    }
                }
            }
            token = set.getNextMarker();
        } while (token != null);
    }

    private boolean isExpired(String path, LocalDateTime now) {
        String folderPrefix = folder + ResourceDescriptor.PATH_SEPARATOR;
        if (!path.startsWith(folderPrefix)) {
            throw invalidPathException(path);
        }

        int folderStart = folderPrefix.length();
        int folderEnd = path.indexOf(ResourceDescriptor.PATH_SEPARATOR, folderStart);
        if (folderEnd < 0) {
            throw invalidPathException(path);
        }

        String folderName = path.substring(folderStart, folderEnd);
        LocalDateTime uploadTs;
        try {
            uploadTs = LocalDateTime.parse(folderName, FOLDER_FORMATTER);
        } catch (DateTimeParseException e) {
            log.warn("Unexpected folder name in temp folder {}: {}", folder, folderName);
            throw invalidPathException(path);
        }

        return uploadTs.plus(ttl).isBefore(now);
    }

    private IllegalArgumentException invalidPathException(String path) {
        return new IllegalArgumentException("Invalid path to temp file: " + path);
    }
}
