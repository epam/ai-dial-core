package com.epam.aidial.core.server.data.config.manifest;

import com.epam.aidial.core.config.Role;

public record AdminRoleManifest(String kind, String name, Role spec) implements AdminTypedManifest {
}
