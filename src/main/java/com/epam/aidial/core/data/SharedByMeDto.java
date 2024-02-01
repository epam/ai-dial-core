package com.epam.aidial.core.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SharedByMeDto {
    Map<String, Set<String>> resourceToUsers;

    public void addUserToResource(String url, String userId) {
        Set<String> users = resourceToUsers.computeIfAbsent(url, k -> new HashSet<>());
        users.add(userId);
    }
}
