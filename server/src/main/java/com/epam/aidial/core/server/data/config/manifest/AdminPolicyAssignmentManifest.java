package com.epam.aidial.core.server.data.config.manifest;

import com.epam.aidial.core.config.policy.PolicyAssignment;

public record AdminPolicyAssignmentManifest(String kind, String name, PolicyAssignment spec) implements AdminTypedManifest {
}
