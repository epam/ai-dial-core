package com.epam.aidial.core.server.service;

import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The disposition of every {@link ResourceTypes} value in every location, written out: a value that is added to {@code ResourceTypes}
 * has no entry here, so this test fails until someone decides what the export does with it.
 */
public class ExportTypePolicyCoverageTest {

    private enum Disposition { ROOT_AND_DEPENDENCY, DEPENDENCY, UNSUPPORTED }

    private static final String PLATFORM = "platform/";
    private static final String PUBLIC = "public/";
    /** A user bucket and the sub-locations of the two eligible locations: never eligible (never eligible). */
    private static final List<String> NEVER_ELIGIBLE = List.of("Users/someone/", "Keys/an-app/", "public/deployments/abc/", "public/publications/p1/",
            "public/sub/", "platform/sub/");

    private static final Map<ResourceTypes, Map<String, Disposition>> EXPECTED = new EnumMap<>(ResourceTypes.class);

    private static void expect(ResourceTypes type, Disposition platform, Disposition publicLocation) {
        EXPECTED.put(type, Map.of(PLATFORM, platform, PUBLIC, publicLocation));
    }

    static {
        // supported: an Application is a root and a dependency in both locations
        expect(ResourceTypes.APPLICATION, Disposition.ROOT_AND_DEPENDENCY, Disposition.ROOT_AND_DEPENDENCY);
        // a ToolSet is a dependency on the platform and in public: the others cannot live in public/
        expect(ResourceTypes.TOOL_SET, Disposition.DEPENDENCY, Disposition.DEPENDENCY);
        expect(ResourceTypes.MODEL, Disposition.DEPENDENCY, Disposition.UNSUPPORTED);
        expect(ResourceTypes.INTERCEPTOR, Disposition.DEPENDENCY, Disposition.UNSUPPORTED);
        expect(ResourceTypes.APP_TYPE_SCHEMA, Disposition.DEPENDENCY, Disposition.UNSUPPORTED);
        expect(ResourceTypes.CATALOG_SCHEMA, Disposition.DEPENDENCY, Disposition.UNSUPPORTED);
        // a File is a dependency in public/ and is not bound to platform; it is never a root
        expect(ResourceTypes.FILE, Disposition.UNSUPPORTED, Disposition.DEPENDENCY);
        // a Skill is a dependency in public/ and is bound to the public bucket only
        expect(ResourceTypes.SKILL, Disposition.UNSUPPORTED, Disposition.DEPENDENCY);
        // a Prompt is a dependency in public/ exactly as stored; Project is not supported
        expect(ResourceTypes.PROMPT, Disposition.UNSUPPORTED, Disposition.DEPENDENCY);
        for (ResourceTypes type : List.of(ResourceTypes.PROJECT)) {
            expect(type, Disposition.UNSUPPORTED, Disposition.UNSUPPORTED);
        }
        // every other type: not a dependency (the fields that name some are not examined) or state and secrets that are never exported
        for (ResourceTypes type : List.of(ResourceTypes.CONVERSATION, ResourceTypes.LIMIT, ResourceTypes.SHARED_WITH_ME, ResourceTypes.SHARED_BY_ME,
                ResourceTypes.INVITATION, ResourceTypes.PUBLICATION, ResourceTypes.RULES, ResourceTypes.API_KEY_DATA, ResourceTypes.NOTIFICATION,
                ResourceTypes.CODE_INTERPRETER_SESSION, ResourceTypes.USER_CONSENT, ResourceTypes.CREDENTIALS, ResourceTypes.EXTERNAL_SERVICE,
                ResourceTypes.ENCRYPTION_KEYS, ResourceTypes.CLIENT_CHANNEL, ResourceTypes.TRANSLATOR, ResourceTypes.ROLE, ResourceTypes.PROJECT_KEY,
                ResourceTypes.POLICY, ResourceTypes.POLICY_ASSIGNMENT, ResourceTypes.ROUTE, ResourceTypes.GLOBAL_SETTINGS,
                ResourceTypes.RESPONSE_MAPPING, ResourceTypes.BACKGROUND_JOB, ResourceTypes.ORG_UNITS, ResourceTypes.ALIAS)) {
            expect(type, Disposition.UNSUPPORTED, Disposition.UNSUPPORTED);
        }
    }

    private static Disposition actual(ResourceTypes type, String location) {
        boolean isDependency = ExportTypePolicy.isSupportedDependency(type, location);
        if (isDependency && ExportTypePolicy.isRoot(type)) {
            return Disposition.ROOT_AND_DEPENDENCY;
        }
        return isDependency ? Disposition.DEPENDENCY : Disposition.UNSUPPORTED;
    }

    @Test
    public void testEveryResourceTypeHasDecidedDispositionAndNewOnesFailHere() {
        assertEquals(Set.copyOf(Arrays.asList(ResourceTypes.values())), EXPECTED.keySet(),
                "a ResourceTypes value has no disposition: decide it and add it to this table");
    }

    @Test
    public void testDispositionOfEveryTypeInPlatformAndPublicIsTheExpectedOne() {
        EXPECTED.forEach((type, byLocation) -> byLocation.forEach((location, expected) ->
                assertEquals(expected, actual(type, location), type + " in " + location)));
    }

    @Test
    public void testNoTypeIsSupportedInUserBucketOrSubLocation() {
        for (String location : NEVER_ELIGIBLE) {
            for (ResourceTypes type : ResourceTypes.values()) {
                assertEquals(Disposition.UNSUPPORTED, actual(type, location), type + " in " + location);
            }
        }
    }

    @Test
    public void testSupportedDependencyIsAlsoBoundToItsBucket() {
        EXPECTED.forEach((type, byLocation) -> {
            if (byLocation.get(PLATFORM) != Disposition.UNSUPPORTED) {
                assertTrue(ExportTypePolicy.isBound(type, "platform"), type + " is not bound to platform");
            }
            if (byLocation.get(PUBLIC) != Disposition.UNSUPPORTED) {
                assertTrue(ExportTypePolicy.isBound(type, "public"), type + " is not bound to public");
            }
        });
    }
}
