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
     * a new one if none exists.
     *
     * @param cekDescriptor descriptor of the CEK resource
     * @return decrypted CEK bytes
     */
    byte[] getOrCreateKey(ResourceDescriptor cekDescriptor);

    /**
     * Generates a new Content Encryption Key (CEK) for the specified resource descriptor,
     * stores the encrypted key, and returns the decrypted CEK bytes.
     *
     * @param cekDescriptor the descriptor identifying the CEK resource for which the key is to be created
     * @return the decrypted CEK bytes
     */
    byte[] createKey(ResourceDescriptor cekDescriptor);
}
