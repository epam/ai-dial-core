package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class ResourceCredentialsService {


    // TODO: replace with persistent storage
    private final Map<String, List<ResourceCredentials>> resourceCredentialsMap = new HashMap<>();


    public void addResourceCredentials(ResourceCredentials resourceCredentials) {
        resourceCredentialsMap.computeIfAbsent(resourceCredentials.getResourceId(), k -> new ArrayList<>()).add(resourceCredentials);
    }

    public List<ResourceCredentials> getAllResourceCredentials(String resourceId) {
        return resourceCredentialsMap.getOrDefault(resourceId, new ArrayList<>());
    }

    public void updateResourceCredentials(String resourceId,
                                         List<ResourceCredentials> resourceCredentials) {
        if (!resourceCredentialsMap.containsKey(resourceId)) {
            throw new ResourceNotFoundException("Credentials for Resource %s not found".formatted(resourceId));
        }

        if (resourceCredentials.isEmpty()) {
            resourceCredentialsMap.remove(resourceId);
        }

        resourceCredentialsMap.put(resourceId, resourceCredentials);
    }

}
