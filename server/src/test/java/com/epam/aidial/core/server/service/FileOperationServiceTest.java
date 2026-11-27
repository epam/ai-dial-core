package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.data.FileTarget;
import com.epam.aidial.core.server.service.resource.ComplexResourceHandler;
import com.epam.aidial.core.server.service.resource.ComplexResourceService;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.data.FileMetadata;
import com.epam.aidial.core.storage.data.UserMetadata;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FileOperationServiceTest {

    private ResourceService resources;
    private ComplexResourceService complex;
    private LockService locks;
    private InvitationService invitations;
    private ShareService shares;
    private OrgUnitService units;
    private FileOperationService service;
    private final FileTarget source = target("projects/public/source/files/a.txt");
    private final FileTarget destination = target("skills/public/destination/files/a.txt");
    private final FileTarget plain = target("files/public/plain.txt");

    @BeforeEach
    void init() {
        resources = mock(ResourceService.class);
        complex = mock(ComplexResourceService.class);
        locks = mock(LockService.class);
        invitations = mock(InvitationService.class);
        shares = mock(ShareService.class);
        units = mock(OrgUnitService.class);
        service = new FileOperationService(resources, complex, locks, invitations, shares, units);
        when(locks.underBucketLocks(anyCollection(), any())).thenAnswer(invocation -> {
            Supplier<?> function = invocation.getArgument(1);
            return function.get();
        });
        when(resources.lockResource(any())).thenReturn(mock(LockService.Lock.class));
        when(complex.getFileSnapshotLocked(source.resource(), source.filePath())).thenReturn(snapshot(source));
        when(complex.getFileSnapshotLocked(destination.resource(), destination.filePath())).thenReturn(snapshot(destination));
        when(resources.getResourceMetadataLocked(plain.descriptor())).thenReturn(snapshot(plain).metadata());
        when(resources.copyResourceLocked(any(), any(), any(), anyBoolean())).thenReturn(true);
        when(resources.deleteResourceLocked(any(), eq(EtagHeader.ANY))).thenReturn(true);
    }

    @Test
    void testComplexCopyUsesProviderSnapshotWithoutDeletingOrSharing() {
        operate(source, destination, false, false);
        verify(complex).importFileLocked(eq(destination.resource()), any(ComplexResourceHandler.class), eq(destination.filePath()),
                eq(source.descriptor()), any(FileMetadata.class), eq(false), eq("author"));
        verify(complex, never()).deleteFileLocked(any(), any(), any(), any());
        verifyNoInteractions(shares, invitations, units);
    }

    @Test
    void testCrossResourceMovePreflightsAndCommitsDestinationBeforeSource() {
        operate(source, destination, true, false);
        InOrder order = inOrder(units, complex);
        order.verify(units).requireSameUnit(source.resource(), destination.resource());
        order.verify(complex).getFileSnapshotLocked(source.resource(), source.filePath());
        order.verify(complex).validateFileDeleteLocked(eq(source.resource()), any(), eq(source.filePath()));
        order.verify(complex).importFileLocked(eq(destination.resource()), any(), eq(destination.filePath()),
                eq(source.descriptor()), any(), eq(false), eq("author"));
        order.verify(complex).deleteFileLocked(eq(source.resource()), any(), eq(source.filePath()), eq("author"));
        verifyNoInteractions(shares, invitations);
    }

    @Test
    void testSameResourceCopyAndMoveUseOneEngineTransaction() {
        FileTarget other = target("projects/public/source/files/b.txt");
        for (boolean move : List.of(false, true)) {
            operate(source, other, move, true);
            verify(complex).copyFileLocked(eq(source.resource()), any(), eq("a.txt"), eq("b.txt"), eq(move), eq(true), eq("author"));
        }
        verify(complex, never()).getFileSnapshotLocked(any(), any());
        verify(complex, never()).importFileLocked(any(), any(), any(), any(), any(), anyBoolean(), any());
        verify(complex, never()).deleteFileLocked(any(), any(), any(), any());
        verify(resources, times(2)).lockResource(ComplexResourceService.getMarkerLockDescriptor(source.resource()));
    }

    @Test
    void testSameResourceMissingSourceIsBadRequestAndOtherFailuresPropagate() {
        FileTarget other = target("projects/public/source/files/b.txt");
        doThrow(new HttpException(HttpStatus.NOT_FOUND, "missing")).when(complex)
                .copyFileLocked(any(), any(), any(), any(), anyBoolean(), anyBoolean(), any());
        assertThrows(IllegalArgumentException.class, () -> operate(source, other, true, false));
        doThrow(new HttpException(HttpStatus.CONFLICT, "exists")).when(complex)
                .copyFileLocked(any(), any(), any(), any(), anyBoolean(), anyBoolean(), any());
        assertEquals(HttpStatus.CONFLICT, assertThrows(HttpException.class, () -> operate(source, other, false, false)).getStatus());
    }

    @Test
    void testPlainToComplexCopyDoesNotReadOrDeleteSourceAgain() {
        operate(plain, destination, false, true);
        verify(complex).importFileLocked(eq(destination.resource()), any(), eq(destination.filePath()),
                eq(plain.descriptor()), any(), eq(true), eq("author"));
        verify(resources, never()).getResourceBytes(any());
        verify(resources, never()).deleteResourceLocked(any(), any());
        verifyNoInteractions(invitations, shares);
    }

    @Test
    void testPlainMoveRevokesPrivateSourceGrantsOnlyAfterDelete() {
        FileTarget privateSource = privateTarget();
        when(resources.getResourceMetadataLocked(privateSource.descriptor())).thenReturn(snapshot(privateSource).metadata());
        operate(privateSource, destination, true, false);
        InOrder order = inOrder(complex, resources, invitations, shares);
        order.verify(resources).getResourceMetadataLocked(privateSource.descriptor());
        order.verify(complex).importFileLocked(eq(destination.resource()), any(), eq(destination.filePath()),
                eq(privateSource.descriptor()), any(), eq(false), eq("author"));
        order.verify(resources).deleteResourceLocked(privateSource.descriptor(), EtagHeader.ANY);
        order.verify(invitations).cleanUpResourceLink("bucket", "private/", privateSource.descriptor());
        order.verify(shares).revokeSharedResource("bucket", "private/", privateSource.descriptor());
    }

    @Test
    void testPlainPublicMoveDoesNotRevokeGrants() {
        operate(plain, destination, true, false);
        verify(resources).deleteResourceLocked(plain.descriptor(), EtagHeader.ANY);
        verifyNoInteractions(invitations, shares);
    }

    @Test
    void testComplexToPlainUsesAlreadyLockedCopyAndDelete() {
        operate(source, plain, true, true);
        InOrder order = inOrder(complex, resources);
        order.verify(complex).getFileSnapshotLocked(source.resource(), source.filePath());
        order.verify(complex).validateFileDeleteLocked(eq(source.resource()), any(), eq(source.filePath()));
        ArgumentCaptor<UserMetadata> metadata = ArgumentCaptor.forClass(UserMetadata.class);
        order.verify(resources).copyResourceLocked(eq(source.descriptor()), eq(plain.descriptor()), metadata.capture(), eq(true));
        order.verify(complex).deleteFileLocked(eq(source.resource()), any(), eq(source.filePath()), eq("author"));
        assertEquals(ResourceTypes.FILE.name(), metadata.getValue().getResourceType());
        assertEquals("file-etag", metadata.getValue().getEtag());
        assertEquals("original author", metadata.getValue().getAuthor());
        assertEquals(10L, metadata.getValue().getCreatedAt());
        assertEquals(20L, metadata.getValue().getUpdatedAt());
        verifyNoInteractions(invitations, shares);
    }

    @Test
    void testPlainDestinationConflictDoesNotDeleteComplexSource() {
        when(resources.copyResourceLocked(any(), any(), any(UserMetadata.class), eq(false))).thenReturn(false);
        assertEquals(HttpStatus.CONFLICT, assertThrows(HttpException.class, () -> operate(source, plain, true, false)).getStatus());
        verify(complex, never()).deleteFileLocked(any(), any(), any(), any());
    }

    @Test
    void testMissingSourcesNeverWriteDestination() {
        when(complex.getFileSnapshotLocked(source.resource(), source.filePath())).thenReturn(null);
        when(resources.getResourceMetadataLocked(plain.descriptor())).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> operate(source, destination, false, false));
        assertThrows(IllegalArgumentException.class, () -> operate(plain, destination, true, false));
        verify(complex, never()).importFileLocked(any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void testSourceDeletePreflightFailureNeverWritesDestination() {
        doThrow(new HttpException(HttpStatus.BAD_REQUEST, "manifest required"))
                .when(complex).validateFileDeleteLocked(eq(source.resource()), any(), eq(source.filePath()));
        assertThrows(HttpException.class, () -> operate(source, destination, true, false));
        verify(complex, never()).importFileLocked(any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void testDestinationFailureDoesNotDeleteSourceOrRevokeShares() {
        when(complex.importFileLocked(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new HttpException(HttpStatus.NOT_FOUND, "create with PUT /v2/skills/public/destination"));
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(HttpException.class, () -> operate(source, destination, true, false)).getStatus());
        verify(complex, never()).deleteFileLocked(any(), any(), any(), any());
        verifyNoInteractions(invitations, shares);
    }

    @Test
    void testSourceDeleteFailureRetainsDestinationAndShares() {
        FileTarget privateSource = privateTarget();
        when(resources.getResourceMetadataLocked(privateSource.descriptor())).thenReturn(snapshot(privateSource).metadata()).thenReturn(null);
        when(resources.deleteResourceLocked(privateSource.descriptor(), EtagHeader.ANY)).thenThrow(new IllegalStateException("delete failed"));
        assertThrows(IllegalStateException.class, () -> operate(privateSource, destination, true, false));
        verify(complex).importFileLocked(eq(destination.resource()), any(), eq(destination.filePath()),
                eq(privateSource.descriptor()), any(), eq(false), eq("author"));
        verifyNoInteractions(invitations, shares);
        ArgumentCaptor<UserMetadata> metadata = ArgumentCaptor.forClass(UserMetadata.class);
        verify(resources).copyResourceLocked(eq(destination.descriptor()), eq(privateSource.descriptor()), metadata.capture(), eq(true));
        assertEquals("file-etag", metadata.getValue().getEtag());
        assertEquals("original author", metadata.getValue().getAuthor());
        assertEquals(10L, metadata.getValue().getCreatedAt());
        assertEquals(20L, metadata.getValue().getUpdatedAt());
        doReturn(true).when(resources).deleteResourceLocked(privateSource.descriptor(), EtagHeader.ANY);
        doReturn(snapshot(privateSource).metadata()).when(resources).getResourceMetadataLocked(privateSource.descriptor());
        operate(privateSource, destination, true, true);
        verify(shares).revokeSharedResource("bucket", "private/", privateSource.descriptor());
    }

    @Test
    void testRestoreFailureIsAttachedToDeleteError() {
        when(resources.getResourceMetadataLocked(plain.descriptor())).thenReturn(snapshot(plain).metadata()).thenReturn(null);
        when(resources.deleteResourceLocked(plain.descriptor(), EtagHeader.ANY)).thenThrow(new IllegalStateException("delete failed"));
        when(resources.copyResourceLocked(eq(destination.descriptor()), eq(plain.descriptor()), any(UserMetadata.class), eq(true)))
                .thenThrow(new IllegalStateException("restore unavailable"));
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> operate(plain, destination, true, false));
        assertEquals("delete failed", error.getMessage());
        assertEquals(1, error.getSuppressed().length);
        assertEquals("restore unavailable", error.getSuppressed()[0].getMessage());
        verifyNoInteractions(invitations, shares);
    }

    @Test
    void testMissingRestoreBackupIsAttachedToDeleteError() {
        when(resources.getResourceMetadataLocked(plain.descriptor())).thenReturn(snapshot(plain).metadata()).thenReturn(null);
        when(resources.deleteResourceLocked(plain.descriptor(), EtagHeader.ANY)).thenThrow(new IllegalStateException("delete failed"));
        when(resources.copyResourceLocked(eq(destination.descriptor()), eq(plain.descriptor()), any(UserMetadata.class), eq(true))).thenReturn(false);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> operate(plain, destination, true, false));
        assertEquals(1, error.getSuppressed().length);
        verifyNoInteractions(invitations, shares);
    }

    @Test
    void testUnexpectedPlainDeleteAbsenceIsAnErrorWithoutGrantCleanup() {
        when(resources.deleteResourceLocked(plain.descriptor(), EtagHeader.ANY)).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> operate(plain, destination, true, false));
        verifyNoInteractions(invitations, shares);
    }

    @Test
    void testComplexSourceDeleteFailureDoesNotRollBackDestination() {
        when(complex.deleteFileLocked(any(), any(), any(), any())).thenThrow(new IllegalStateException("source commit failed"));
        assertThrows(IllegalStateException.class, () -> operate(source, destination, true, false));
        verify(complex).importFileLocked(eq(destination.resource()), any(), eq(destination.filePath()),
                eq(source.descriptor()), any(), eq(false), eq("author"));
        verify(complex, never()).restoreFileLocked(any(), any(), any(), any(), any());
        verifyNoInteractions(shares, invitations);
    }

    @Test
    void testComplexSourceAppliedDeleteRestoresFromComplexOrPlainDestination() {
        for (FileTarget backup : List.of(destination, plain)) {
            when(complex.getFileSnapshotLocked(source.resource(), source.filePath())).thenReturn(snapshot(source)).thenReturn(null);
            doThrow(new IllegalStateException("source marker acknowledgement lost")).when(complex).deleteFileLocked(any(), any(), any(), any());
            assertThrows(IllegalStateException.class, () -> operate(source, backup, true, true));
            verify(complex).restoreFileLocked(eq(source.resource()), any(), eq(source.filePath()), eq(backup.descriptor()), any(FileMetadata.class));
        }
        verifyNoInteractions(shares, invitations);
    }

    @Test
    void testChangedComplexSourceSnapshotIsRestoredWithSavedMetadata() {
        ComplexResourceService.LockedFileSnapshot saved = snapshot(source);
        ComplexResourceService.LockedFileSnapshot changed = snapshot(source);
        changed.metadata().setEtag("changed-etag");
        when(complex.getFileSnapshotLocked(source.resource(), source.filePath())).thenReturn(saved).thenReturn(changed);
        doThrow(new IllegalStateException("source marker acknowledgement lost")).when(complex).deleteFileLocked(any(), any(), any(), any());
        assertThrows(IllegalStateException.class, () -> operate(source, destination, true, true));
        ArgumentCaptor<FileMetadata> metadata = ArgumentCaptor.forClass(FileMetadata.class);
        verify(complex).restoreFileLocked(eq(source.resource()), any(), eq(source.filePath()), eq(destination.descriptor()), metadata.capture());
        assertEquals("file-etag", metadata.getValue().getEtag());
        assertEquals("original author", metadata.getValue().getAuthor());
        verifyNoInteractions(shares, invitations);
    }

    @Test
    void testComplexSourceRestoreFailureIsAttachedToOriginalDeleteError() {
        when(complex.getFileSnapshotLocked(source.resource(), source.filePath())).thenReturn(snapshot(source)).thenReturn(null);
        doThrow(new IllegalStateException("source marker acknowledgement lost")).when(complex).deleteFileLocked(any(), any(), any(), any());
        doThrow(new IllegalStateException("source restore unavailable")).when(complex).restoreFileLocked(any(), any(), any(), any(), any());
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> operate(source, destination, true, true));
        assertEquals("source marker acknowledgement lost", error.getMessage());
        assertEquals(1, error.getSuppressed().length);
        assertEquals("source restore unavailable", error.getSuppressed()[0].getMessage());
        verifyNoInteractions(shares, invitations);
    }

    @Test
    void testPlainSourceFailureRestoresDurabilityRatherThanTrustingCacheMetadata() {
        doThrow(new IllegalStateException("source provider delete failed")).when(resources).deleteResourceLocked(plain.descriptor(), EtagHeader.ANY);
        assertThrows(IllegalStateException.class, () -> operate(plain, destination, true, true));
        verify(resources).copyResourceLocked(eq(destination.descriptor()), eq(plain.descriptor()), any(UserMetadata.class), eq(true));
        verifyNoInteractions(shares, invitations);
    }

    @Test
    void testMissingCommittedBackupIsAttachedToOriginalDeleteError() {
        when(complex.getFileSnapshotLocked(source.resource(), source.filePath())).thenReturn(snapshot(source)).thenReturn(null);
        when(complex.getFileSnapshotLocked(destination.resource(), destination.filePath())).thenReturn(null);
        doThrow(new IllegalStateException("source marker acknowledgement lost")).when(complex).deleteFileLocked(any(), any(), any(), any());
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> operate(source, destination, true, true));
        assertEquals(1, error.getSuppressed().length);
        verifyNoInteractions(shares, invitations);
    }

    @Test
    void testRecordLockOrderIsDescendingAndBucketsComeFirst() {
        ResourceDescriptor sourceLock = ComplexResourceService.getMarkerLockDescriptor(source.resource());
        ResourceDescriptor destinationLock = ComplexResourceService.getMarkerLockDescriptor(destination.resource());
        LockService.Lock first = mock(LockService.Lock.class);
        LockService.Lock second = mock(LockService.Lock.class);
        when(resources.lockResource(destinationLock)).thenReturn(first);
        when(resources.lockResource(sourceLock)).thenReturn(second);
        operate(source, destination, false, false);
        InOrder order = inOrder(locks, resources, complex, first, second);
        order.verify(locks).underBucketLocks(eq(List.of("public/", "public/")), any());
        order.verify(resources).lockResource(destinationLock);
        order.verify(resources).lockResource(sourceLock);
        order.verify(complex).getFileSnapshotLocked(source.resource(), source.filePath());
        order.verify(complex).importFileLocked(eq(destination.resource()), any(), eq(destination.filePath()),
                eq(source.descriptor()), any(), eq(false), eq("author"));
        order.verify(second).close();
        order.verify(first).close();
    }

    @Test
    void testAcquisitionFailureClosesEarlierRecordLocks() {
        ResourceDescriptor firstRecord = ComplexResourceService.getMarkerLockDescriptor(destination.resource());
        ResourceDescriptor secondRecord = ComplexResourceService.getMarkerLockDescriptor(source.resource());
        LockService.Lock first = mock(LockService.Lock.class);
        when(resources.lockResource(firstRecord)).thenReturn(first);
        when(resources.lockResource(secondRecord)).thenThrow(new IllegalStateException("lock failed"));
        assertThrows(IllegalStateException.class, () -> operate(source, destination, false, false));
        verify(first).close();
        verifyNoInteractions(complex);
    }

    @Test
    void testOrgUnitMoveRefusalPrecedesStorageReads() {
        doThrow(new HttpException(HttpStatus.CONFLICT, "different owners")).when(units).requireSameUnit(source.resource(), destination.resource());
        assertEquals(HttpStatus.CONFLICT, assertThrows(HttpException.class, () -> operate(source, destination, true, false)).getStatus());
        verifyNoInteractions(resources, complex, invitations, shares);
    }

    @Test
    void testInvalidTargetsNeverAcquireLocks() {
        for (String invalid : List.of("projects/public/root", "projects/public/source/files/", "projects/public/source/files/refs/")) {
            assertThrows(HttpException.class, () -> operate(source, target(invalid), false, false));
        }
        assertThrows(IllegalArgumentException.class, () -> operate(source, source, false, false));
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(HttpException.class,
                () -> operate(source, target("projects/public/source/files/refs/%76/a"), false, false)).getStatus());
        verifyNoInteractions(locks, complex, resources);
    }

    private void operate(FileTarget from, FileTarget to, boolean move, boolean overwrite) {
        service.copyFile(from, to, move, overwrite, "author");
    }

    private static FileTarget target(String url) {
        return ResourceDescriptorFactory.fromFileUrl(url, null);
    }

    private static FileTarget privateTarget() {
        ResourceDescriptor descriptor = new ResourceDescriptor(ResourceTypes.FILE,
                "plain.txt", List.of(), "bucket", "private/", false);
        return new FileTarget(descriptor, descriptor, null);
    }

    private static ComplexResourceService.LockedFileSnapshot snapshot(FileTarget target) {
        FileMetadata metadata = new FileMetadata(target.descriptor(), 4, "text/plain");
        metadata.setEtag("file-etag");
        metadata.setAuthor("original author");
        metadata.setCreatedAt(10L);
        metadata.setUpdatedAt(20L);
        return new ComplexResourceService.LockedFileSnapshot(target.descriptor(), metadata);
    }
}
