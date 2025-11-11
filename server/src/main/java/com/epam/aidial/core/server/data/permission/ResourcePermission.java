package com.epam.aidial.core.server.data.permission;

import com.epam.aidial.core.config.ResourceAccessType;
import lombok.Data;

import java.util.Set;

@Data
public class ResourcePermission {
    private String url;
    private Set<ResourceAccessType> permissions;
}
