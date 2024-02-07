package com.epam.aidial.core.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Invitation {
    String id;
    Set<ResourceLink> resources;
    long createdAt;
    long expireAt;
}
