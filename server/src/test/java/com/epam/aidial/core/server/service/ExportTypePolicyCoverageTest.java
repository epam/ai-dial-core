package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The disposition of every {@link ResourceTypes} value in every location, written out: a value that is added has no entry here, so this
 * test fails until someone decides what the export does with it.
 */
public class ExportTypePolicyCoverageTest {

    private enum Disposition { ROOT_AND_DEPENDENCY, DEPENDENCY, UNSUPPORTED }

    private static final String PLATFORM = "platform/";
    private static final String PUBLIC = "public/";
    /** A user bucket and the sub-locations of the two eligible locations: never eligible. */
    private static final List<String> NEVER_ELIGIBLE = List.of("Users/someone/", "Keys/an-app/", "public/deployments/abc/", "public/publications/p1/",
            "public/sub/", "platform/sub/");

    private static final Map<ResourceTypes, Map<String, Disposition>> EXPECTED = new EnumMap<>(ResourceTypes.class);

    private static void expect(ResourceTypes type, Disposition platform, Disposition publicLocation) {
        EXPECTED.put(type, Map.of(PLATFORM, platform, PUBLIC, publicLocation));
    }

    static {
        expect(ResourceTypes.APPLICATION, Disposition.ROOT_AND_DEPENDENCY, Disposition.ROOT_AND_DEPENDENCY);
        // a ToolSet is a dependency on the platform and in public: the others cannot live in public/
        expect(ResourceTypes.TOOL_SET, Disposition.DEPENDENCY, Disposition.DEPENDENCY);
        expect(ResourceTypes.MODEL, Disposition.DEPENDENCY, Disposition.UNSUPPORTED);
        expect(ResourceTypes.INTERCEPTOR, Disposition.DEPENDENCY, Disposition.UNSUPPORTED);
        expect(ResourceTypes.APP_TYPE_SCHEMA, Disposition.DEPENDENCY, Disposition.UNSUPPORTED);
        expect(ResourceTypes.CATALOG_SCHEMA, Disposition.DEPENDENCY, Disposition.UNSUPPORTED);
        expect(ResourceTypes.FILE, Disposition.UNSUPPORTED, Disposition.DEPENDENCY);
        expect(ResourceTypes.SKILL, Disposition.UNSUPPORTED, Disposition.DEPENDENCY);
        // a Prompt is a dependency in public/ exactly as stored; Project is not supported
        expect(ResourceTypes.PROMPT, Disposition.UNSUPPORTED, Disposition.DEPENDENCY);
        expect(ResourceTypes.PROJECT, Disposition.UNSUPPORTED, Disposition.UNSUPPORTED);
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

    @Test
    public void testEveryExaminedTypeHasItsEntityAndRedactionRule() {
        List<ResourceTypes> examined = Arrays.stream(ResourceTypes.values())
                .filter(type -> ExportTypePolicy.resolveContent(type) == ExportTypePolicy.Content.EXAMINED).toList();

        assertFalse(examined.isEmpty());
        assertEquals(examined, ExportTypePolicy.listExaminedTypes());
        for (ResourceTypes type : examined) {
            assertTrue(Deployment.class.isAssignableFrom(ExportTypePolicy.resolveEntityClass(type)), type + " has no entity class");
            assertNotNull(ExportTypePolicy.resolveRedaction(type), type + " has no rule that removes its secrets");
            assertNull(ExportTypePolicy.findDeployment(new Config(), type, "absent"), type + " has no way to find its entities");
        }
    }

    @Test
    public void testTypeThatIsNotExaminedHasNoEntityAndNoRedactionRule() {
        for (ResourceTypes type : ResourceTypes.values()) {
            if (ExportTypePolicy.resolveContent(type) != ExportTypePolicy.Content.EXAMINED) {
                assertNull(ExportTypePolicy.resolveRedaction(type), type.toString());
                assertThrows(IllegalArgumentException.class, () -> ExportTypePolicy.resolveEntityClass(type), type.toString());
                assertNull(ExportTypePolicy.findDeployment(new Config(), type, "absent"), type.toString());
            }
        }
    }

    @Test
    public void testFindDeploymentReadsTheEntityOfTheTypeFromTheConfig() {
        Config config = new Config();
        Application application = new Application();
        Model model = new Model();
        ToolSet toolSet = new ToolSet();
        Interceptor interceptor = new Interceptor();
        config.setApplications(Map.of("n", application));
        config.setModels(Map.of("n", model));
        config.setToolsets(Map.of("n", toolSet));
        config.setInterceptors(Map.of("n", interceptor));

        assertSame(application, ExportTypePolicy.findDeployment(config, ResourceTypes.APPLICATION, "n"));
        assertSame(model, ExportTypePolicy.findDeployment(config, ResourceTypes.MODEL, "n"));
        assertSame(toolSet, ExportTypePolicy.findDeployment(config, ResourceTypes.TOOL_SET, "n"));
        assertSame(interceptor, ExportTypePolicy.findDeployment(config, ResourceTypes.INTERCEPTOR, "n"));
        assertEquals(Application.class, ExportTypePolicy.resolveEntityClass(ResourceTypes.APPLICATION));
        assertEquals(Model.class, ExportTypePolicy.resolveEntityClass(ResourceTypes.MODEL));
        assertEquals(ToolSet.class, ExportTypePolicy.resolveEntityClass(ResourceTypes.TOOL_SET));
        assertEquals(Interceptor.class, ExportTypePolicy.resolveEntityClass(ResourceTypes.INTERCEPTOR));
    }
}
