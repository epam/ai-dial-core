package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ResponseMapping;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import lombok.AllArgsConstructor;

import javax.annotation.Nullable;

@AllArgsConstructor
public class ResponseMappingService {

    private final ResourceService resourceService;
    private final EncryptionService encryptionService;

    public void saveMapping(ProxyContext context, String dialResponseId, ResponseMapping mapping) {
        ResourceDescriptor descriptor = getDescriptor(context, dialResponseId);
        resourceService.putResource(descriptor, ProxyUtil.convertToString(mapping), EtagHeader.ANY);
    }

    @Nullable
    public ResponseMapping getMapping(ProxyContext context, String dialResponseId) {
        ResourceDescriptor descriptor = getDescriptor(context, dialResponseId);
        String json = resourceService.getResource(descriptor);
        return ProxyUtil.convertToObject(json, ResponseMapping.class);
    }

    public void deleteMapping(ProxyContext context, String dialResponseId) {
        ResourceDescriptor descriptor = getDescriptor(context, dialResponseId);
        resourceService.deleteResource(descriptor, EtagHeader.ANY);
    }

    private ResourceDescriptor getDescriptor(ProxyContext context, String dialResponseId) {
        String bucketLocation = BucketBuilder.buildInitiatorBucket(context);
        String bucketName = encryptionService.encrypt(bucketLocation);
        return ResourceDescriptorFactory.fromDecoded(ResourceTypes.RESPONSE_MAPPING, bucketName, bucketLocation, dialResponseId);
    }
}
