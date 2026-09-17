package com.epam.aidial.core.credentials.encryption;

import com.epam.aidial.core.credentials.exception.CekEncryptionException;
import com.epam.aidial.core.credentials.keymanagement.KeyManagementService;
import com.epam.aidial.core.storage.migration.BucketMigrationState;
import com.epam.aidial.core.storage.migration.BucketMigrationStates;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.mutable.MutableObject;

/**
 * Default implementation of {@link ContentEncryptionKeyManager}.
 *
 * <p>Retrieves an encrypted CEK from a {@link ResourceService}, decrypts it using
 * a {@link KeyManagementService}, and returns the decrypted CEK.
 *
 * <p>If the CEK does not exist, generates a new one via
 * {@link ContentEncryptionKeyGenerator}, encrypts it with the KMS, and stores
 * it in the {@link ResourceService} — unless the bucket is being migrated, where a missing key means the
 * migration has not put it there yet rather than that the bucket never had one.
 */
@Slf4j
@RequiredArgsConstructor
public class ContentEncryptionKeyManagerImpl implements ContentEncryptionKeyManager {

    private final ResourceService resourceService;
    private final ContentEncryptionKeyGenerator contentEncryptionKeyGenerator;
    private final KeyManagementService keyManagementService;
    private final BucketMigrationStates migrationStates;

    @Override
    public byte[] getOrCreateKey(ResourceDescriptor cekDescriptor) {
        // Read first, and only fall through to the read-modify-write when there is nothing to read.
        // Reading a key that already exists is a read, and routing it through computeResourceBytes made it
        // a write as far as the migration's write barrier is concerned: sealing a bucket then refused every
        // decrypt of its content for as long as the copy took, though a sealed bucket is supposed to keep
        // serving reads.
        byte[] existing = resourceService.getResourceBytes(cekDescriptor);
        if (existing != null) {
            try {
                return keyManagementService.decrypt(existing);
            } catch (CekEncryptionException e) {
                log.warn("Could not decrypt the content encryption key at {}, replacing it",
                        cekDescriptor.getAbsoluteFilePath(), e);
            }
        }

        requireKeyIsNotExpected(cekDescriptor);

        MutableObject<byte[]> cekHolder = new MutableObject<>();
        resourceService.computeResourceBytes(cekDescriptor, encryptedCek -> {
            // Re-read under the lock: another caller may have created the key since the read above.
            if (encryptedCek != null) {
                try {
                    byte[] cek = keyManagementService.decrypt(encryptedCek);
                    cekHolder.setValue(cek);
                    return encryptedCek;
                } catch (CekEncryptionException e) {
                    return createKey(cekHolder);
                }
            } else {
                return createKey(cekHolder);
            }
        });
        return cekHolder.get();
    }

    /**
     * Minting a key is how a bucket gets its first one, and it is the wrong answer for a bucket that already
     * had one. A bucket that is moving or has moved must already have its key at the resolved path, because
     * the migrator copies {@code encryption_keys} before anything else in the bucket. Finding none there
     * means the copy did not run or did not finish — and creating one would write a fresh key over a bucket
     * whose content was encrypted with the old one, silently and unrecoverably, since the new key is stored.
     *
     * <p>Reached only when there is no key to read, which is why the read above comes first: during a copy
     * this is the diagnostic, while a plain decrypt of an existing key is never blocked at all.
     *
     * <p>Deliberately narrow, and narrow to the window rather than to the outcome: only a bucket that is
     * being copied right now expects a key it cannot find. A store that has never been migrated, a greenfield
     * deployment serving one layout, and a bucket that finished migrating all leave first-key creation
     * untouched — the last of those matters most, since a migrated bucket stays migrated and would otherwise
     * never be able to store its first credential.
     */
    private void requireKeyIsNotExpected(ResourceDescriptor cekDescriptor) {
        String bucketLocation = cekDescriptor.getBucketLocation();
        BucketMigrationState state = migrationStates.resolve(bucketLocation);
        // Exhaustive rather than "anything but LEGACY": a state added later has to be classified here
        // instead of quietly falling on whichever side the condition happened to put it.
        boolean keyShouldAlreadyExist = switch (state) {
            // Not migrating: a missing key is the first key, which is how every bucket gets one.
            case LEGACY -> false;
            // Being copied, and the migrator copies encryption_keys first, so it should already be here.
            case MIGRATING -> true;
            // The copy is finished. A key missing now was missing before, because the bucket never had
            // encrypted content — refusing here would block the first credential a bucket ever stores, for
            // as long as the bucket stays migrated, which is forever.
            case MIGRATED -> false;
        };

        if (keyShouldAlreadyExist) {
            throw new CekEncryptionException(("No content encryption key for %s, which is %s. Refusing to "
                    + "create one: the bucket's existing content is encrypted with the key the migration "
                    + "should have copied, and a new key would make it unreadable")
                    .formatted(bucketLocation, state));
        }
    }

    private byte[] createKey(MutableObject<byte[]> cekHolder) {
        byte[] cek = contentEncryptionKeyGenerator.generate();
        cekHolder.setValue(cek);
        return keyManagementService.encrypt(cek);
    }

}
