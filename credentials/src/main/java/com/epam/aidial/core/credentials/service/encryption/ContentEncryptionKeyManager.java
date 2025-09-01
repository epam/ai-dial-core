package com.epam.aidial.core.credentials.service.encryption;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;

public interface ContentEncryptionKeyManager {
    byte[] createKey(ResourceDescriptor cekDescriptor);

    byte[] getOrCreateKey(ResourceDescriptor cekDescriptor);

    byte[] getKey(ResourceDescriptor cekDescriptor);
}
