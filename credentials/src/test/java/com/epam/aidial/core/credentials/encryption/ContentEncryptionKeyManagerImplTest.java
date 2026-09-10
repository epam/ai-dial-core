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

        doAnswer(invocation -> {
            Function<byte[], byte[]> function = invocation.getArgument(1);
            function.apply(encryptedCek);
            return null;
        }).when(resourceService).computeResourceBytes(eq(resourceDescriptor), any());

        when(keyManagementService.decrypt(encryptedCek)).thenReturn(decryptedCek);

        byte[] result = contentEncryptionKeyManager.getOrCreateKey(resourceDescriptor);

        assertArrayEquals(decryptedCek, result);
        verify(keyManagementService).decrypt(encryptedCek);
        verifyNoInteractions(keyGenerator);
    }

    @Test
    void testGetOrCreateKey_CekExists_DecryptionFails() {
        ResourceDescriptor resourceDescriptor = mock(ResourceDescriptor.class);
        byte[] encryptedCek = new byte[]{1, 2, 3};
        byte[] newCek = new byte[]{7, 8, 9};
        byte[] encryptedNewCek = new byte[]{10, 11, 12};

        doAnswer(invocation -> {
            Function<byte[], byte[]> function = invocation.getArgument(1);
            function.apply(encryptedCek);
            return null;
        }).when(resourceService).computeResourceBytes(eq(resourceDescriptor), any());

        when(keyManagementService.decrypt(encryptedCek)).thenThrow(new CekEncryptionException("fail"));
        when(keyGenerator.generate()).thenReturn(newCek);
        when(keyManagementService.encrypt(newCek)).thenReturn(encryptedNewCek);

        byte[] result = contentEncryptionKeyManager.getOrCreateKey(resourceDescriptor);

        assertArrayEquals(newCek, result);
        verify(keyManagementService).decrypt(encryptedCek);
        verify(keyGenerator).generate();
        verify(keyManagementService).encrypt(newCek);
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

        doAnswer(invocation -> {
            Function<byte[], byte[]> function = invocation.getArgument(1);
            function.apply(null);
            return null;
        }).when(resourceService).computeResourceBytes(eq(resourceDescriptor), any());

        // The migrator copies encryption_keys first, so a missing key here means the copy did not finish.
        // Creating one would write a fresh key over content encrypted with the old one.
        CekEncryptionException error = assertThrows(CekEncryptionException.class,
                () -> contentEncryptionKeyManager.getOrCreateKey(resourceDescriptor));

        assertTrue(error.getMessage().contains("Refusing to create one"), error.getMessage());
        verifyNoInteractions(keyGenerator);
    }

    @Test
    void testGetOrCreateKey_NoCek_BucketFinishedMigrating_CreatesIt() {
        ResourceDescriptor resourceDescriptor = mock(ResourceDescriptor.class);
        when(resourceDescriptor.getBucketLocation()).thenReturn("Users/u1/");
        when(migrationStates.resolve("Users/u1/")).thenReturn(BucketMigrationState.MIGRATED);
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
}
