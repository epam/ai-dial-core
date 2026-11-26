package com.epam.aidial.core.server.data.config.manifest;

import com.epam.aidial.core.config.policy.PolicyPackage;

public record AdminPackageManifest(String kind, String name, PolicyPackage spec) implements AdminTypedManifest {
}
