package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsEncryptionService;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class AuthSettingsResolver {

    private final EncryptionService encryptionService;
    private final ResourceAuthSettingsEncryptionService authSettingsEncryptionService;

    // For resource-stored toolsets returns a transient decrypted COPY of authSettings
    // so the deployment held in ProxyContext stays ciphertext. Config-defined toolsets
    // are already plaintext and returned as-is.
    public ResourceAuthSettings resolve(ToolSet toolSet, ProxyContext context) {
        ResourceAuthSettings stored = toolSet.getAuthSettings();
        if (context.getConfig().isDeploymentExists(toolSet.getName())) {
            return stored;
        }
        // toolSet.getName() is already percent-encoded; don't re-encode or AAD won't match.
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl(toolSet.getName(), encryptionService);
        BucketInfo bucketInfo = new BucketInfo(descriptor.getBucketName(), descriptor.getBucketLocation());
        ResourceAuthSettings copy = stored.toBuilder().build();
        authSettingsEncryptionService.decrypt(descriptor.getUrl(), bucketInfo, copy);
        return copy;
    }
}
