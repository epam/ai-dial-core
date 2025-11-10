package com.epam.aidial.core.server.data.permission;

import lombok.Data;

import java.util.List;

@Data
public class ResourcePermissionList {
    private List<ResourcePermission> resources;
}
