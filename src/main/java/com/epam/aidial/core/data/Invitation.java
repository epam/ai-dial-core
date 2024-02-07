package com.epam.aidial.core.data;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Set;

@Data
@AllArgsConstructor
public class Invitation {
    String id;
    Set<ResourceLink> resources;
    long createdAt;
    long expireAt;
}
