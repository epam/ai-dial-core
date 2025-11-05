package com.epam.aidial.core.credentials.encryption;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;

/**
 * Manages creation and retrieval of decrypted Content Encryption Keys (CEKs)
 * used to encrypt/decrypt sensitive data.
 *
 * <p>Implementations may retrieve encrypted CEKs from storage and decrypt them
 * using a Key Management Service (KMS), or generate and store new CEKs if none exist.
 */
public interface ContentEncryptionKeyManager {
    /**
     * Returns an existing CEK for the given descriptor, or creates and stores
     * a new one if none exists or if CEK can't be decrypted
     *
     * @param cekDescriptor descriptor of the CEK resource
     * @return decrypted CEK bytes
     */
    byte[] getOrCreateKey(ResourceDescriptor cekDescriptor);
}
