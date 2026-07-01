package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.ExternalService;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsEncryptionService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.data.NodeType;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * User-authored external-service definitions (design §9/§10): each is its own resource in the author's
 * bucket at {@code Users/{ownerSub}/external_services/applications/{app_id}/{id}}. Isolation rides on
 * bucket ownership; the {@code client_secret} is encrypted under the owner's CEK.
 */
@RequiredArgsConstructor
public class UserExternalServiceService {

    private static final int LIST_PAGE_SIZE = 1000;

    private final ResourceService resourceService;
    private final ResourceAuthSettingsEncryptionService secretEncryptionService;
    private final EncryptionService bucketEncryptionService;

    public ResourceDescriptor descriptor(String ownerSub, String appPart, String serviceId) {
        String bucketLocation = BucketBuilder.USER_BUCKET_PATTERN.formatted(ownerSub);
        String bucketName = bucketEncryptionService.encrypt(bucketLocation);
        List<String> parentFolders = new ArrayList<>();
        parentFolders.add("applications");
        parentFolders.addAll(Arrays.asList(appPart.split(ResourceDescriptor.PATH_SEPARATOR)));
        return new ResourceDescriptor(ResourceTypes.EXTERNAL_SERVICE, serviceId, parentFolders, bucketName, bucketLocation, false);
    }

    private ResourceDescriptor folderDescriptor(String ownerSub, String appPart) {
        String bucketLocation = BucketBuilder.USER_BUCKET_PATTERN.formatted(ownerSub);
        String bucketName = bucketEncryptionService.encrypt(bucketLocation);
        List<String> segments = new ArrayList<>();
        segments.add("applications");
        segments.addAll(Arrays.asList(appPart.split(ResourceDescriptor.PATH_SEPARATOR)));
        String name = segments.remove(segments.size() - 1);
        return new ResourceDescriptor(ResourceTypes.EXTERNAL_SERVICE, name, segments, bucketName, bucketLocation, true);
    }

    public ExternalService put(String ownerSub, String appPart, String serviceId, ExternalService service, String author) {
        ResourceDescriptor resource = descriptor(ownerSub, appPart, serviceId);
        BucketInfo bucket = new BucketInfo(resource.getBucketName(), resource.getBucketLocation());
        String aad = resource.getAbsoluteFilePath();
        MutableObject<ExternalService> result = new MutableObject<>();
        resourceService.computeResource(resource, EtagHeader.ANY, author, json -> {
            ExternalService existing = json == null ? null : ProxyUtil.convertToObject(json, ExternalService.class);
            decryptSecret(aad, bucket, existing);
            ExternalServiceValidation.validate(serviceId, service, existing == null);
            clearAuthStatuses(service);
            preserveOmittedSecret(service, existing);
            encryptSecret(aad, bucket, service);
            result.setValue(service);
            return ProxyUtil.convertToString(service);
        });
        return result.getValue();
    }

    /** Reads and decrypts the user-authored definition, or {@code null} if the owner has none for this scope. */
    public ExternalService get(String ownerSub, String appPart, String serviceId) {
        ResourceDescriptor resource = descriptor(ownerSub, appPart, serviceId);
        Pair<ResourceItemMetadata, String> stored = resourceService.getResourceWithMetadata(resource, EtagHeader.ANY);
        if (stored == null || stored.getValue() == null) {
            return null;
        }
        ExternalService service = ProxyUtil.convertToObject(stored.getValue(), ExternalService.class);
        decryptSecret(resource.getAbsoluteFilePath(), new BucketInfo(resource.getBucketName(), resource.getBucketLocation()), service);
        return service;
    }

    /** Lists and decrypts all user-authored definitions the owner has for this application (id → definition). */
    public Map<String, ExternalService> list(String ownerSub, String appPart) {
        ResourceDescriptor folder = folderDescriptor(ownerSub, appPart);
        Map<String, ExternalService> result = new LinkedHashMap<>();
        String token = null;
        do {
            ResourceFolderMetadata metadata = resourceService.getFolderMetadata(folder, token, LIST_PAGE_SIZE, false);
            if (metadata == null) {
                break;
            }
            List<? extends MetadataBase> items = metadata.getItems();
            if (items != null) {
                for (MetadataBase item : items) {
                    if (item.getNodeType() == NodeType.ITEM) {
                        ExternalService service = get(ownerSub, appPart, item.getName());
                        if (service != null) {
                            result.put(item.getName(), service);
                        }
                    }
                }
            }
            token = metadata.getNextToken();
        } while (token != null);
        return result;
    }

    public void delete(String ownerSub, String appPart, String serviceId) {
        ResourceDescriptor resource = descriptor(ownerSub, appPart, serviceId);
        if (!resourceService.deleteResource(resource, EtagHeader.ANY)) {
            throw new ResourceNotFoundException("External service '%s' not found".formatted(serviceId));
        }
    }

    private void encryptSecret(String aad, BucketInfo bucket, ExternalService service) {
        if (hasSecret(service)) {
            secretEncryptionService.encrypt(aad, bucket, service.getAuthSettings());
        }
    }

    private void decryptSecret(String aad, BucketInfo bucket, ExternalService service) {
        if (hasSecret(service)) {
            secretEncryptionService.decrypt(aad, bucket, service.getAuthSettings());
        }
    }

    private static boolean hasSecret(ExternalService service) {
        return service != null && service.getAuthSettings() != null && service.getAuthSettings().getClientSecret() != null;
    }

    private static void preserveOmittedSecret(ExternalService service, ExternalService existing) {
        if (existing == null || existing.getAuthSettings() == null || service.getAuthSettings() == null) {
            return;
        }
        if (service.getAuthSettings().getClientSecret() == null && existing.getAuthSettings().getClientSecret() != null) {
            service.getAuthSettings().setClientSecret(existing.getAuthSettings().getClientSecret());
        }
    }

    private static void clearAuthStatuses(ExternalService service) {
        ResourceAuthSettings authSettings = service.getAuthSettings();
        if (authSettings != null) {
            authSettings.setUserLevelAuthStatus(null);
            authSettings.setAppLevelAuthStatus(null);
            authSettings.setGlobalAuthStatus(null);
        }
    }
}
