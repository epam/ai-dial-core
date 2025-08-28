package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.config.ShareResourceLimit;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Collections;
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
    Map<String, ShareResourceLimit> limits;
    @JsonProperty("resourceToUsers")
    Map<String, Set<String>> readableResourceToUsers;
    Map<String, Set<String>> writableResourcesToUsers;
    Map<String, Set<String>> shareableResourcesToUsers;
    Map<String, String> userIdToDisplayName;

    @JsonCreator
    public SharedByMeDto(
            @JsonProperty("resourceToUsers")
            Map<String, Set<String>> readableResourceToUsers,
            @JsonProperty("writableResourcesToUsers")
            Map<String, Set<String>> writableResourcesToUsers,
            @JsonProperty("limits")
            Map<String, ShareResourceLimit> limits,
            @JsonProperty("shareableResourcesToUsers")
            Map<String, Set<String>> shareableResourcesToUsers,
            @JsonProperty("userIdToDisplayName")
            Map<String, String> userIdToDisplayName) {
        this.readableResourceToUsers = readableResourceToUsers;
        this.writableResourcesToUsers = Objects.requireNonNullElseGet(writableResourcesToUsers, HashMap::new);
        this.shareableResourcesToUsers = Objects.requireNonNullElseGet(shareableResourcesToUsers, HashMap::new);
        this.userIdToDisplayName = Objects.requireNonNullElseGet(userIdToDisplayName, HashMap::new);
        this.limits = limits;
    }

    public Set<String> collectUsersForPermissions(String url, Set<ResourceAccessType> permissions) {
        return permissions.stream()
                .flatMap(permission -> getUserMapForPermission(permission).getOrDefault(url, Set.of()).stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    public void addUserToResource(SharedResource resource, String userLocation, String userDisplayName) {
        Set<ResourceAccessType> permissions = resource.getPermissions();
        String url = resource.getUrl();
        for (ResourceAccessType permission : permissions) {
            Set<String> users = getUserMapForPermission(permission)
                    .computeIfAbsent(url, k -> new HashSet<>());
            users.add(userLocation);
        }
        userIdToDisplayName.put(userLocation, userDisplayName);
    }

    public void addUserPermissionsToResource(
            String url,
            Map<String, Set<ResourceAccessType>> userPermissions,
            Map<String, String> userDisplayNames) {
        userPermissions.forEach((user, permissions) -> {
            for (ResourceAccessType permission : permissions) {
                Set<String> users = getUserMapForPermission(permission)
                        .computeIfAbsent(url, k -> new HashSet<>());
                users.add(user);
            }
            userIdToDisplayName.put(user, userDisplayNames.get(user));
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
        refreshDisplayNames();
    }

    public void removePermissionsFromResource(String url, Set<ResourceAccessType> permissionsToRemove) {
        for (ResourceAccessType permission : permissionsToRemove) {
            Map<String, Set<String>> usersMap = getUserMapForPermission(permission);
            usersMap.remove(url);
        }
        refreshDisplayNames();
    }

    private void refreshDisplayNames() {
        Set<String> allUsers = new HashSet<>();
        allUsers.addAll(readableResourceToUsers.values().stream().flatMap(Set::stream).collect(Collectors.toSet()));
        allUsers.addAll(writableResourcesToUsers.values().stream().flatMap(Set::stream).collect(Collectors.toSet()));
        allUsers.addAll(shareableResourcesToUsers.values().stream().flatMap(Set::stream).collect(Collectors.toSet()));
        userIdToDisplayName.keySet().retainAll(allUsers);
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
    public Map<String, Map<String, Set<ResourceAccessType>>> getPerUserPermissions() {
        Map<String, Map<String, Set<ResourceAccessType>>> result = new HashMap<>();
        for (ResourceAccessType permission : ResourceAccessType.ALL) {
            Map<String, Set<String>> usersMap = getUserMapForPermission(permission);
            usersMap.forEach((resource, users) -> {
                Map<String, Set<ResourceAccessType>> userPermissions = result.computeIfAbsent(
                        resource, k -> new HashMap<>());
                for (String user : users) {
                    String displayName = userIdToDisplayName.get(user);
                    if (displayName != null) {
                        Set<ResourceAccessType> permissions = userPermissions.computeIfAbsent(
                                displayName, k -> new HashSet<>());
                        permissions.add(permission);
                    }
                }
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
    public int getNumOfAcceptedUsers(String resourceUrl) {
        Set<String> uniqueUsers = new HashSet<>();
        uniqueUsers.addAll(readableResourceToUsers.getOrDefault(resourceUrl, Collections.emptySet()));
        uniqueUsers.addAll(writableResourcesToUsers.getOrDefault(resourceUrl, Collections.emptySet()));
        return uniqueUsers.size();
    }

    @JsonIgnore
    public void updateLimit(String resourceUrl, ShareResourceLimit limit) {
        if (limits == null) {
            limits = new HashMap<>();
        }
        limits.put(resourceUrl, limit);
    }

    @JsonIgnore
    private Map<String, Set<String>> getUserMapForPermission(ResourceAccessType permission) {
        return switch (permission) {
            case READ -> readableResourceToUsers;
            case WRITE -> writableResourcesToUsers;
            case SHARE -> shareableResourcesToUsers;
        };
    }
}
