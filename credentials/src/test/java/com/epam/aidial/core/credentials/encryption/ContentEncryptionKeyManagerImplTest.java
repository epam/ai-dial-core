package com.epam.aidial.core.credentials.encryption;

import com.epam.aidial.core.credentials.exception.CekEncryptionException;
import com.epam.aidial.core.credentials.keymanagement.KeyManagementService;
import com.epam.aidial.core.storage.migration.BucketMigrationState;
import com.epam.aidial.core.storage.migration.BucketMigrationStates;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
class ContentEncryptionKeyManagerImplTest {

    @Mock
    private ResourceService resourceService;
    @Mock
    private ContentEncryptionKeyGenerator keyGenerator;
    @Mock
    private KeyManagementService keyManagementService;
    @Mock
    private BucketMigrationStates migrationStates;
    @InjectMocks
    private ContentEncryptionKeyManagerImpl contentEncryptionKeyManager;


    @Test
    void testGetOrCreateKey_CekExists_DecryptionSuccess() {
        ResourceDescriptor resourceDescriptor = mock(ResourceDescriptor.class);
        byte[] encryptedCek = new byte[]{1, 2, 3};
        byte[] decryptedCek = new byte[]{4, 5, 6};

        when(resourceService.getResourceBytes(resourceDescriptor)).thenReturn(encryptedCek);
        when(keyManagementService.decrypt(encryptedCek)).thenReturn(decryptedCek);

        byte[] result = contentEncryptionKeyManager.getOrCreateKey(resourceDescriptor);

        assertArrayEquals(decryptedCek, result);
        verify(keyManagementService).decrypt(encryptedCek);
        verifyNoInteractions(keyGenerator);
    }

    @Test
    void testGetOrCreateKey_CekExists_DecryptionFails_RefusesToReplaceIt() {
        ResourceDescriptor resourceDescriptor = mock(ResourceDescriptor.class);
        when(resourceDescriptor.getBucketLocation()).thenReturn("Users/u1/");
        byte[] encryptedCek = new byte[]{1, 2, 3};
        when(resourceService.getResourceBytes(resourceDescriptor)).thenReturn(encryptedCek);
        // Every KMS wraps whatever went wrong — a rotated key, a network fault, a throttled call — as this.
        when(keyManagementService.decrypt(encryptedCek)).thenThrow(new CekEncryptionException("fail"));

        CekEncryptionException error = assertThrows(CekEncryptionException.class,
                () -> contentEncryptionKeyManager.getOrCreateKey(resourceDescriptor));

        // A key that will not open is not a key that is absent. Minting over it would re-key the bucket
        // under whoever was unlucky enough to call during a KMS hiccup, and every ciphertext in it would be
        // lost — silently, since the new key is stored. Whatever the bucket's migration state.
        assertTrue(error.getMessage().contains("cannot be decrypted"), error.getMessage());
        verifyNoInteractions(keyGenerator);
        verify(resourceService, never()).computeResourceBytes(any(), any());
    }

    @Test
    void testGetOrCreateKey_CekAppearsUnderTheLock_ButWillNotDecrypt_RefusesToReplaceIt() {
        ResourceDescriptor resourceDescriptor = mock(ResourceDescriptor.class);
        when(migrationStates.resolve(any())).thenReturn(BucketMigrationState.LEGACY);
        byte[] encryptedCek = new byte[]{1, 2, 3};
        // Nothing to read, then another caller's key is there by the time the lock is held.
        doAnswer(invocation -> {
            Function<byte[], byte[]> function = invocation.getArgument(1);
            function.apply(encryptedCek);
            return null;
        }).when(resourceService).computeResourceBytes(eq(resourceDescriptor), any());
        when(keyManagementService.decrypt(encryptedCek)).thenThrow(new CekEncryptionException("fail"));

        assertThrows(CekEncryptionException.class, () -> contentEncryptionKeyManager.getOrCreateKey(resourceDescriptor));
        verifyNoInteractions(keyGenerator);
    }

    @Test
    void testGetOrCreateKey_CekDoesNotExist() {
        ResourceDescriptor resourceDescriptor = mock(ResourceDescriptor.class);
        when(migrationStates.resolve(any())).thenReturn(BucketMigrationState.LEGACY);
        byte[] newCek = new byte[]{7, 8, 9};
        byte[] encryptedNewCek = new byte[]{10, 11, 12};

        doAnswer(invocation -> {
            Function<byte[], byte[]> function = invocation.getArgument(1);
            function.apply(null);
            return null;
        }).when(resourceService).computeResourceBytes(eq(resourceDescriptor), any());

        when(keyGenerator.generate()).thenReturn(newCek);
        when(keyManagementService.encrypt(newCek)).thenReturn(encryptedNewCek);

        byte[] result = contentEncryptionKeyManager.getOrCreateKey(resourceDescriptor);

        assertArrayEquals(newCek, result);
        verify(keyGenerator).generate();
        verify(keyManagementService).encrypt(newCek);
    }

    @Test
    void testGetOrCreateKey_NoCek_BucketIsMigrating_RefusesToCreate() {
        ResourceDescriptor resourceDescriptor = mock(ResourceDescriptor.class);
        when(resourceDescriptor.getBucketLocation()).thenReturn("Users/u1/");
        when(migrationStates.resolve("Users/u1/")).thenReturn(BucketMigrationState.MIGRATING);


        // The migrator copies encryption_keys first, so a missing key here means the copy did not finish.
        // Creating one would write a fresh key over content encrypted with the old one.
        CekEncryptionException error = assertThrows(CekEncryptionException.class,
                () -> contentEncryptionKeyManager.getOrCreateKey(resourceDescriptor));

        assertTrue(error.getMessage().contains("the copy has not delivered it yet"), error.getMessage());
        verifyNoInteractions(keyGenerator);
    }

    @Test
    void testGetOrCreateKey_NoCek_BucketFinishedMigrating_CreatesIt() {
        ResourceDescriptor resourceDescriptor = mock(ResourceDescriptor.class);
        when(resourceDescriptor.getBucketLocation()).thenReturn("Users/u1/");
        when(migrationStates.resolve("Users/u1/")).thenReturn(BucketMigrationState.MIGRATED);
        // Nothing at the legacy path either, so the bucket never held encrypted content.
        when(resourceService.hasResourceAtLegacyPath(resourceDescriptor)).thenReturn(false);
        byte[] generatedCek = new byte[]{7, 8, 9};
        byte[] encryptedCek = new byte[]{10, 11, 12};

        doAnswer(invocation -> {
            Function<byte[], byte[]> function = invocation.getArgument(1);
            function.apply(null);
            return null;
        }).when(resourceService).computeResourceBytes(eq(resourceDescriptor), any());
        when(keyGenerator.generate()).thenReturn(generatedCek);
        when(keyManagementService.encrypt(generatedCek)).thenReturn(encryptedCek);

        // The copy is over: a key missing now was missing before it, because the bucket never had encrypted
        // content. A bucket stays migrated, so refusing here would block its first credential forever.
        assertArrayEquals(generatedCek, contentEncryptionKeyManager.getOrCreateKey(resourceDescriptor));
    }

    @Test
    void testGetOrCreateKey_CekExists_BucketIsSealed_StillReads() {
        ResourceDescriptor resourceDescriptor = mock(ResourceDescriptor.class);
        byte[] encryptedCek = new byte[]{1, 2, 3};
        byte[] decryptedCek = new byte[]{4, 5, 6};
        when(resourceService.getResourceBytes(resourceDescriptor)).thenReturn(encryptedCek);
        when(keyManagementService.decrypt(encryptedCek)).thenReturn(decryptedCek);

        // A sealed bucket refuses writes and keeps serving reads. Reading an existing key must not go
        // through computeResourceBytes, which the write barrier rejects for the whole duration of a copy.
        assertArrayEquals(decryptedCek, contentEncryptionKeyManager.getOrCreateKey(resourceDescriptor));

        verifyNoInteractions(keyGenerator);
        verify(resourceService, never()).computeResourceBytes(any(), any());
    }

    @Test
    void testGetOrCreateKey_NoCek_ButLegacyTreeHasOne_RefusesToCreate() {
        ResourceDescriptor resourceDescriptor = mock(ResourceDescriptor.class);
        when(resourceDescriptor.getBucketLocation()).thenReturn("Users/u1/");
        when(migrationStates.resolve("Users/u1/")).thenReturn(BucketMigrationState.MIGRATED);
        // The copy is over and there is no key here, but the legacy tree — which a migration copies rather
        // than moves — still has one. So the bucket did hold encrypted content and the key did not arrive.
        when(resourceService.hasResourceAtLegacyPath(resourceDescriptor)).thenReturn(true);

        CekEncryptionException error = assertThrows(CekEncryptionException.class,
                () -> contentEncryptionKeyManager.getOrCreateKey(resourceDescriptor));

        assertTrue(error.getMessage().contains("the copy did not deliver it"), error.getMessage());
        verifyNoInteractions(keyGenerator);
    }
}
