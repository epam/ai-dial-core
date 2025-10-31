package com.epam.aidial.core.server.data.permission;

import lombok.Data;

import java.util.List;

@Data
public class PerRequestReceiver {
    private List<ResourcePermission> resources;
    private String receiver;
}
