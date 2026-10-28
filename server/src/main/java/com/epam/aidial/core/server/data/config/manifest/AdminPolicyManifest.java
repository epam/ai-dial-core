package com.epam.aidial.core.server.data.config.manifest;

import com.epam.aidial.core.config.policy.Policy;

public record AdminPolicyManifest(String kind, String name, Policy spec) implements AdminTypedManifest {
}
