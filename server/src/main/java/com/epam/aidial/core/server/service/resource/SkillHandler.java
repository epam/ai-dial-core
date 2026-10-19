package com.epam.aidial.core.server.service.resource;

import com.epam.aidial.core.storage.resource.ResourceType;
import com.epam.aidial.core.storage.resource.ResourceTypes;

/**
 * Handler for the {@code skills} group. A skill must contain a {@code SKILL.md} at its root.
 */
public class SkillHandler extends BaseFrontmatterHandler {

    public static final String MANIFEST = "SKILL.md";

    public SkillHandler() {
        super(MANIFEST, "Skill");
    }

    @Override
    public ResourceType resourceType() {
        return ResourceTypes.SKILL;
    }
}
