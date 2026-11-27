package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.data.FileTarget;
import com.epam.aidial.core.server.service.resource.ComplexResourceHandler;
import com.epam.aidial.core.server.service.resource.ComplexResourceService;
import com.epam.aidial.core.server.service.resource.ProjectHandler;
import com.epam.aidial.core.server.service.resource.SkillHandler;
import com.epam.aidial.core.storage.data.FileMetadata;
import com.epam.aidial.core.storage.data.UserMetadata;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceType;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.epam.aidial.core.storage.util.RedisUtil;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Coordinates files only; callers authorize both roots before entering this blocking service. */
@RequiredArgsConstructor
public class FileOperationService {

    private static final Map<ResourceType, ComplexResourceHandler> HANDLERS = Map.of(
            ResourceTypes.SKILL, new SkillHandler(), ResourceTypes.PROJECT, new ProjectHandler());

    private final ResourceService resourceService;
    private final ComplexResourceService complexResourceService;
    private final LockService lockService;
    private final InvitationService invitationService;
    private final ShareService shareService;
    private final OrgUnitService orgUnitService;

    public void copyFile(FileTarget source, FileTarget destination, boolean move, boolean overwrite, String author) {
        source.validatePath();
        destination.validatePath();
        source.requireFile();
        destination.requireFile();
        if (source.descriptor().getUrl().equals(destination.descriptor().getUrl())) {
            throw new IllegalArgumentException("Source and destination files must have different URLs");
        }

        List<String> buckets = List.of(source.resource().getBucketLocation(), destination.resource().getBucketLocation());
        lockService.underBucketLocks(buckets, () -> {
            if (move) {
                orgUnitService.requireSameUnit(source.resource(), destination.resource());
            }
            List<ResourceDescriptor> records = List.of(lockDescriptor(source), lockDescriptor(destination)).stream()
                    .distinct()
                    // ResourceService.copyResource takes the larger Redis record key first.
                    .sorted(Comparator.comparing((ResourceDescriptor record) -> RedisUtil.redisKey(record, resourceService.getPrefix())).reversed())
                    .toList();
            List<LockService.Lock> locks = new ArrayList<>(records.size());
            try {
                for (ResourceDescriptor record : records) {
                    locks.add(resourceService.lockResource(record));
                }
                copyFileLocked(source, destination, move, overwrite, author);
                return null;
            } finally {
                for (LockService.Lock lock : locks.reversed()) {
                    lock.close();
                }
            }
        });
    }

    private void copyFileLocked(FileTarget source, FileTarget destination, boolean move, boolean overwrite, String author) {
        if (source.isComplexFile() && destination.isComplexFile() && source.resource().equals(destination.resource())) {
            try {
                complexResourceService.copyFileLocked(source.resource(), HANDLERS.get(source.resource().getType()),
                        source.filePath(), destination.filePath(), move, overwrite, author);
            } catch (HttpException e) {
                if (e.getStatus() == HttpStatus.NOT_FOUND) {
                    throw missingSource(source);
                }
                throw e;
            }
            return;
        }

        ComplexResourceService.LockedFileSnapshot snapshot = source.isComplexFile()
                ? complexResourceService.getFileSnapshotLocked(source.resource(), source.filePath())
                : plainSnapshot(source);
        if (snapshot == null) {
            throw missingSource(source);
        }
        if (move && source.isComplexFile()) {
            complexResourceService.validateFileDeleteLocked(source.resource(), HANDLERS.get(source.resource().getType()), source.filePath());
        }

        if (destination.isComplexFile()) {
            complexResourceService.importFileLocked(destination.resource(), HANDLERS.get(destination.resource().getType()),
                    destination.filePath(), snapshot.descriptor(), snapshot.metadata(), overwrite, author);
        } else {
            UserMetadata metadata = buildUserMetadata(snapshot.metadata(), destination.descriptor());
            if (!resourceService.copyResourceLocked(snapshot.descriptor(), destination.descriptor(), metadata, overwrite)) {
                throw new HttpException(HttpStatus.CONFLICT, "Destination file already exists: " + destination.descriptor().getUrl());
            }
        }

        if (move) {
            try {
                if (source.isComplexFile()) {
                    complexResourceService.deleteFileLocked(source.resource(), HANDLERS.get(source.resource().getType()), source.filePath(), author);
                } else if (!resourceService.deleteResourceLocked(source.descriptor(), EtagHeader.ANY)) {
                    throw new IllegalStateException("Source file could not be deleted: " + source.descriptor().getUrl());
                }
            } catch (RuntimeException e) {
                try {
                    // A plain cache record can outlive its blob deletion; only the complex marker proves survival here.
                    ComplexResourceService.LockedFileSnapshot current = source.isComplexFile()
                            ? complexResourceService.getFileSnapshotLocked(source.resource(), source.filePath()) : null;
                    FileMetadata saved = snapshot.metadata();
                    FileMetadata actual = current == null ? null : current.metadata();
                    boolean unchanged = actual != null && actual.getContentLength() == saved.getContentLength()
                            && Objects.equals(actual.getEtag(), saved.getEtag())
                            && Objects.equals(actual.getContentType(), saved.getContentType())
                            && Objects.equals(actual.getAuthor(), saved.getAuthor())
                            && Objects.equals(actual.getCreatedAt(), saved.getCreatedAt())
                            && Objects.equals(actual.getUpdatedAt(), saved.getUpdatedAt());
                    if (!unchanged) {
                        ComplexResourceService.LockedFileSnapshot backup = destination.isComplexFile()
                                ? complexResourceService.getFileSnapshotLocked(destination.resource(), destination.filePath()) : plainSnapshot(destination);
                        if (backup == null) {
                            throw new IllegalStateException("Committed destination could not restore the source: " + source.descriptor().getUrl());
                        }
                        if (source.isComplexFile()) {
                            complexResourceService.restoreFileLocked(source.resource(), HANDLERS.get(source.resource().getType()),
                                    source.filePath(), backup.descriptor(), saved);
                        } else if (!resourceService.copyResourceLocked(backup.descriptor(), source.descriptor(),
                                buildUserMetadata(saved, source.descriptor()), true)) {
                            throw new IllegalStateException("Committed destination could not restore the source: " + source.descriptor().getUrl());
                        }
                    }
                } catch (RuntimeException restoreFailure) {
                    e.addSuppressed(restoreFailure);
                }
                throw e;
            }
            if (!source.isComplexFile() && source.resource().isPrivate()) {
                String bucket = source.resource().getBucketName();
                String location = source.resource().getBucketLocation();
                invitationService.cleanUpResourceLink(bucket, location, source.descriptor());
                shareService.revokeSharedResource(bucket, location, source.descriptor());
            }
        }
    }

    private static UserMetadata buildUserMetadata(FileMetadata metadata, ResourceDescriptor target) {
        UserMetadata userMetadata = new UserMetadata();
        userMetadata.setAuthor(metadata.getAuthor());
        userMetadata.setCreatedAt(metadata.getCreatedAt());
        userMetadata.setUpdatedAt(metadata.getUpdatedAt());
        userMetadata.setEtag(metadata.getEtag());
        userMetadata.setResourceType(target.getType().name());
        return userMetadata;
    }

    private ComplexResourceService.LockedFileSnapshot plainSnapshot(FileTarget source) {
        FileMetadata metadata = (FileMetadata) resourceService.getResourceMetadataLocked(source.descriptor());
        return metadata == null ? null : new ComplexResourceService.LockedFileSnapshot(source.descriptor(), metadata);
    }

    private static ResourceDescriptor lockDescriptor(FileTarget target) {
        return target.isComplexFile() ? ComplexResourceService.getMarkerLockDescriptor(target.resource()) : target.descriptor();
    }

    private static IllegalArgumentException missingSource(FileTarget source) {
        return new IllegalArgumentException("Source file does not exist: " + source.descriptor().getUrl());
    }
}
