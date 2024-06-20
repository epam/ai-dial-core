package com.epam.aidial.core.data;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.function.Predicate.not;

@Data
public class SharedByMeDto {
    @JsonProperty("resourceToUsers")
    Map<String, Set<String>> readableResourceToUsers;
    Map<String, Map<String, Set<ResourceAccessType>>> resourcesWithPermissions;

    @JsonCreator
    public SharedByMeDto(
            @JsonProperty("resourceToUsers")
            Map<String, Set<String>> readableResourceToUsers,
            @JsonProperty("resourcesWithPermissions")
            Map<String, Map<String, Set<ResourceAccessType>>> resourcesWithPermissions) {
        this.readableResourceToUsers = readableResourceToUsers;
        this.resourcesWithPermissions = resourcesWithPermissions == null
                ? oldMapToReadPermissions(readableResourceToUsers)
                : resourcesWithPermissions;
    }

    public void addUserToResource(SharedResource resource, String userLocation) {
        Set<ResourceAccessType> permissions = resource.permissions();
        String url = resource.url();
        if (permissions.contains(ResourceAccessType.READ)) {
            Set<String> users = readableResourceToUsers.computeIfAbsent(url, k -> new HashSet<>());
            users.add(userLocation);
        }

        Map<String, Set<ResourceAccessType>> usersWithPermissions =
                resourcesWithPermissions.computeIfAbsent(url, k -> new HashMap<>());

        Set<ResourceAccessType> existingPermissions =
                usersWithPermissions.computeIfAbsent(userLocation, k -> EnumSet.noneOf(ResourceAccessType.class));
        existingPermissions.addAll(permissions);
    }

    public void addUsersToResource(String url, Map<String, Set<ResourceAccessType>> usersPermissions) {
        usersPermissions.forEach((user, permissions) -> {
            if (permissions.contains(ResourceAccessType.READ)) {
                Set<String> users = readableResourceToUsers.computeIfAbsent(url, k -> new HashSet<>());
                users.add(user);
            }

            Set<ResourceAccessType> existingPermissions =
                    resourcesWithPermissions.computeIfAbsent(url, k -> new HashMap<>())
                            .computeIfAbsent(user, k -> EnumSet.noneOf(ResourceAccessType.class));
            existingPermissions.addAll(permissions);
        });
    }

    private static Map<String, Map<String, Set<ResourceAccessType>>> oldMapToReadPermissions(
            Map<String, Set<String>> readableResourceToUsers) {
        return readableResourceToUsers.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().stream()
                                .collect(Collectors.toMap(
                                        Function.identity(),
                                        user -> EnumSet.of(ResourceAccessType.READ)))));
    }
}
