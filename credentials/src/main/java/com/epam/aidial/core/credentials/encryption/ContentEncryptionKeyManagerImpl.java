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

        requireCreationIsSafe(cekDescriptor, existing != null);

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
    private void requireCreationIsSafe(ResourceDescriptor cekDescriptor, boolean unreadableKeyPresent) {
        String bucketLocation = cekDescriptor.getBucketLocation();
        BucketMigrationState state = migrationStates.resolve(bucketLocation);
        // Exhaustive rather than "anything but LEGACY": a state added later has to be classified here
        // instead of quietly falling on whichever side the condition happened to put it.
        boolean keyShouldAlreadyExist = switch (state) {
            // Not migrating: a missing key is the first key, which is how every bucket gets one.
            case LEGACY -> false;
            // Being copied, and the migrator copies encryption_keys first, so it should already be here.
            case MIGRATING -> true;
            // The copy is over, so a key could be missing for either of two reasons, and they need opposite
            // answers: the bucket never had encrypted content, or the copy failed to bring its key across.
            // The legacy tree still holds the answer, because a migration copies rather than moves — a key
            // there and not here is one that did not arrive, and minting over it would strand every
            // ciphertext in the bucket.
            case MIGRATED -> resourceService.hasResourceAtLegacyPath(cekDescriptor);
        };

        if (keyShouldAlreadyExist) {
            // Say which of the two it is. They look identical from here and lead an operator to entirely
            // different places: one is a copy that has not delivered, the other is key material that will
            // not open.
            String problem = unreadableKeyPresent
                    ? "its content encryption key cannot be decrypted"
                    : "it has no content encryption key";
            String reason = state == BucketMigrationState.MIGRATING
                    ? "the copy has not delivered it yet"
                    : "one is present at its legacy path, so the copy did not deliver it";

            throw new CekEncryptionException(("Refusing to create a content encryption key for %s: %s, the "
                    + "bucket is %s, and %s. The bucket's content is encrypted with the key that should be "
                    + "there, and a new one would make all of it unreadable")
                    .formatted(bucketLocation, problem, state, reason));
        }
    }

    private byte[] createKey(MutableObject<byte[]> cekHolder) {
        byte[] cek = contentEncryptionKeyGenerator.generate();
        cekHolder.setValue(cek);
        return keyManagementService.encrypt(cek);
    }

}
