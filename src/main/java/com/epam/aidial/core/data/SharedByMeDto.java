package com.epam.aidial.core.data;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SharedByMeDto {
    @JsonProperty("resourceToUsers")
    Map<String, Set<String>> readableResourceToUsers;
    Map<String, Set<String>> writableResourcesToUsers;

    @JsonCreator
    public SharedByMeDto(
            @JsonProperty("resourceToUsers")
            Map<String, Set<String>> readableResourceToUsers,
            @JsonProperty("writableResourcesToUsers")
            Map<String, Set<String>> writableResourcesToUsers) {
        this.readableResourceToUsers = readableResourceToUsers;
        this.writableResourcesToUsers = Objects.requireNonNullElseGet(writableResourcesToUsers, HashMap::new);
    }

    public Set<String> collectUsersForPermissions(String url, Set<ResourceAccessType> permissions) {
        return permissions.stream()
                .flatMap(permission -> getUserMapForPermission(permission).getOrDefault(url, Set.of()).stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    public void addUserToResource(SharedResource resource, String userLocation) {
        Set<ResourceAccessType> permissions = resource.permissions();
        String url = resource.url();
        for (ResourceAccessType permission : permissions) {
            Set<String> users = getUserMapForPermission(permission)
                    .computeIfAbsent(url, k -> new HashSet<>());
            users.add(userLocation);
        }
    }

    public void addUserPermissionsToResource(String url, Map<String, Set<ResourceAccessType>> userPermissions) {
        userPermissions.forEach((user, permissions) -> {
            for (ResourceAccessType permission : permissions) {
                Set<String> users = getUserMapForPermission(permission)
                        .computeIfAbsent(url, k -> new HashSet<>());
                users.add(user);
            }
        });
    }

    public void removeUserFromResource(String url, String user) {
        for (ResourceAccessType permission : ResourceAccessType.ALL) {
            Map<String, Set<String>> usersMap = getUserMapForPermission(permission);
            Set<String> users = usersMap.get(url);
            if (users != null) {
                users.remove(user);
                if (users.isEmpty()) {
                    usersMap.remove(url);
                }
            }
        }
    }

    public void removePermissionsFromResource(String url, Set<ResourceAccessType> permissionsToRemove) {
        for (ResourceAccessType permission : permissionsToRemove) {
            Map<String, Set<String>> usersMap = getUserMapForPermission(permission);
            usersMap.remove(url);
        }
    }

    @JsonIgnore
    public Map<String, Set<ResourceAccessType>> getAggregatedPermissions() {
        Map<String, Set<ResourceAccessType>> result = new HashMap<>();
        for (ResourceAccessType permission : ResourceAccessType.ALL) {
            Map<String, Set<String>> usersMap = getUserMapForPermission(permission);
            usersMap.forEach((resource, users) -> {
                Set<ResourceAccessType> permissions = result.computeIfAbsent(
                        resource, k -> EnumSet.noneOf(ResourceAccessType.class));
                permissions.add(permission);
            });
        }

        return result;
    }

    @JsonIgnore
    public Map<String, Set<ResourceAccessType>> getUserPermissions(String url) {
        Map<String, Set<ResourceAccessType>> result = new HashMap<>();
        for (ResourceAccessType permission : ResourceAccessType.ALL) {
            Set<String> users = getUserMapForPermission(permission).getOrDefault(url, Set.of());
            for (String user : users) {
                Set<ResourceAccessType> permissions =
                        result.computeIfAbsent(user, k -> EnumSet.noneOf(ResourceAccessType.class));
                permissions.add(permission);
            }
        }

        return result;
    }

    @JsonIgnore
    private Map<String, Set<String>> getUserMapForPermission(ResourceAccessType permission) {
        return switch (permission) {
            case READ -> readableResourceToUsers;
            case WRITE -> writableResourcesToUsers;
        };
    }
}
