package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.permission.PerRequestReceiver;
import com.epam.aidial.core.server.data.permission.PerRequestReceiverList;
import com.epam.aidial.core.server.data.permission.PerRequestSharedData;
import com.epam.aidial.core.server.data.permission.ResourcePermission;
import com.epam.aidial.core.server.data.permission.ResourcePermissionList;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
public class PerRequestPermissionService {

    private  final ApiKeyStore apiKeyStore;
    private final AccessService accessService;
    private final EncryptionService encryptionService;

    public PerRequestPermissionService(ApiKeyStore apiKeyStore, AccessService accessService, EncryptionService encryptionService) {
        this.apiKeyStore = apiKeyStore;
        this.accessService = accessService;
        this.encryptionService = encryptionService;
    }

    public void grant(ProxyContext context, PerRequestReceiver request) {
        String key = context.getApiKeyData().getPerRequestKey();
        for (ResourcePermission resourcePermission : request.getResources()) {
            ResourceDescriptor resource = ResourceDescriptorFactory.fromAnyUrl(resourcePermission.getUrl(), encryptionService);
            Map<ResourceDescriptor, Set<ResourceAccessType>> result = accessService.lookupPermissions(Set.of(resource), context);
            Set<ResourceAccessType> actual = result.get(resource);
            Set<ResourceAccessType> expected = resourcePermission.getPermissions();
            if (!actual.containsAll(expected)) {
                throw new PermissionDeniedException("Access to resource is forbidden: " + resourcePermission.getUrl());
            }
        }
        apiKeyStore.updatePerRequestApiKey(key, json -> {
            ApiKeyData apiKeyData = convert(json, key);
            addPermissions(apiKeyData, request);
            return ProxyUtil.convertToString(apiKeyData);
        });
    }

    private static ApiKeyData convert(String json, String key) {
        ApiKeyData apiKeyData = ProxyUtil.convertToObject(json, ApiKeyData.class);
        if (apiKeyData == null) {
            String errorMsg = String.format("Per request API key is not found: %s", key);
            log.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }
        return apiKeyData;
    }

    private void addPermissions(ApiKeyData apiKeyData, PerRequestReceiver request) {
        String receiver = request.getReceiver();
        Map<String, PerRequestSharedData> resourcePermissions = apiKeyData.getReceivers().computeIfAbsent(receiver, k -> new HashMap<>());
        for (ResourcePermission resourcePermission : request.getResources()) {
            String url = resourcePermission.getUrl();
            PerRequestSharedData sharedData = resourcePermissions.computeIfAbsent(url, k -> new PerRequestSharedData(new HashSet<>()));
            sharedData.permissions().addAll(resourcePermission.getPermissions());
        }
    }

    public void revoke(ProxyContext context, PerRequestReceiver request) {
        String key = context.getApiKeyData().getPerRequestKey();
        apiKeyStore.updatePerRequestApiKey(key, json -> {
            ApiKeyData apiKeyData = convert(json, key);
            removePermissions(apiKeyData, request);
            return ProxyUtil.convertToString(apiKeyData);
        });
    }

    private void removePermissions(ApiKeyData apiKeyData, PerRequestReceiver request) {
        String receiver = request.getReceiver();
        Map<String, PerRequestSharedData> resourcePermissions = apiKeyData.getReceivers().get(receiver);
        if (resourcePermissions == null) {
            return;
        }
        for (ResourcePermission resourcePermission : request.getResources()) {
            String url = resourcePermission.getUrl();
            PerRequestSharedData sharedData = resourcePermissions.get(url);
            if (sharedData == null) {
                continue;
            }
            Set<ResourceAccessType> permissions = sharedData.permissions();
            permissions.removeAll(resourcePermission.getPermissions());
            if (permissions.isEmpty()) {
                resourcePermissions.remove(url);
            }
        }
    }

    public PerRequestReceiverList listReceivers(ProxyContext context) {
        List<PerRequestReceiver> result = context.getApiKeyData().getReceivers().entrySet().stream().map(entry -> {
            PerRequestReceiver receiver = new PerRequestReceiver();
            receiver.setReceiver(entry.getKey());
            receiver.setResources(entry.getValue().values().stream().map(data -> {
                ResourcePermission resourcePermission = new ResourcePermission();
                resourcePermission.setPermissions(data.permissions());
                resourcePermission.setUrl(resourcePermission.getUrl());
                return resourcePermission;
            }).toList());
            return receiver;
        }).toList();
        PerRequestReceiverList list = new PerRequestReceiverList();
        list.setReceivers(result);
        return list;
    }

    public ResourcePermissionList listResourcePermissions(ProxyContext context) {
        List<ResourcePermission> result = context.getApiKeyData().getPerRequestSharedResources().entrySet()
                .stream().map(entry -> {
                    ResourcePermission resourcePermission = new ResourcePermission();
                    resourcePermission.setUrl(entry.getKey());
                    resourcePermission.setPermissions(entry.getValue().permissions());
                    return resourcePermission;
                }).toList();
        ResourcePermissionList list = new ResourcePermissionList();
        list.setResources(result);
        return list;
    }
}
