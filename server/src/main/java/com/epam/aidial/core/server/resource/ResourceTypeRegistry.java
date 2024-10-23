package com.epam.aidial.core.server.resource;

import lombok.experimental.UtilityClass;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@UtilityClass
public class ResourceTypeRegistry {
    private final Map<String, ResourceType> nameToType = new ConcurrentHashMap<>();
    private final Map<String, ResourceType> groupToType = new ConcurrentHashMap<>();

    private record ResourceTypeInternal(String name, String group, boolean external) implements ResourceType {

    }

    public ResourceType register(String typeName, String groupName, boolean external) {
        var type = new ResourceTypeInternal(typeName, groupName, external);
        groupToType.computeIfAbsent(groupName, key -> type);
        return nameToType.computeIfAbsent(typeName, key -> type);
    }

    public ResourceType getByType(String typeName) {
        return nameToType.get(typeName);
    }

    public ResourceType getByGroup(String group) {
        return groupToType.get(group);
    }
}
