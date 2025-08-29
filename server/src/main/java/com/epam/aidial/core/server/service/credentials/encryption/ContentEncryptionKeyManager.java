package com.epam.aidial.core.server.service.credentials.encryption;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;

public interface ContentEncryptionKeyManager {
    byte[] createKey(ResourceDescriptor cekDescriptor);

    byte[] getOrCreateKey(ResourceDescriptor cekDescriptor);

    byte[] getKey(ResourceDescriptor cekDescriptor);
}
