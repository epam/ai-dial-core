package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The disposition of every {@link ResourceTypes} value in every location, written out: a value that is added has no entry here, so this
 * test fails until someone decides what the dependency list does with it.
 */
public class DependencyTypePolicyTest {

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
        // the others cannot live in public/
        expect(ResourceTypes.MODEL, Disposition.DEPENDENCY, Disposition.UNSUPPORTED);
        expect(ResourceTypes.INTERCEPTOR, Disposition.DEPENDENCY, Disposition.UNSUPPORTED);
        expect(ResourceTypes.APP_TYPE_SCHEMA, Disposition.DEPENDENCY, Disposition.UNSUPPORTED);
        expect(ResourceTypes.CATALOG_SCHEMA, Disposition.DEPENDENCY, Disposition.UNSUPPORTED);
        expect(ResourceTypes.FILE, Disposition.UNSUPPORTED, Disposition.DEPENDENCY);
        expect(ResourceTypes.SKILL, Disposition.UNSUPPORTED, Disposition.DEPENDENCY);
        // a Prompt is a dependency in public/; Project is not supported
        expect(ResourceTypes.PROMPT, Disposition.UNSUPPORTED, Disposition.DEPENDENCY);
        expect(ResourceTypes.PROJECT, Disposition.UNSUPPORTED, Disposition.UNSUPPORTED);
        // every other type: not a dependency (the fields that name some are not examined) or state and secrets that are never listed
        for (ResourceTypes type : Arrays.stream(ResourceTypes.values()).filter(type -> !EXPECTED.containsKey(type)).toList()) {
            expect(type, Disposition.UNSUPPORTED, Disposition.UNSUPPORTED);
        }
    }

    private static Disposition actual(ResourceTypes type, String location) {
        boolean isDependency = DependencyTypePolicy.isSupportedDependency(type, location);
        if (isDependency && DependencyTypePolicy.isRoot(type)) {
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
    public void testTypesAreEitherExaminedOrLeavesOrUnsupported() {
        for (ResourceTypes type : ResourceTypes.values()) {
            DependencyTypePolicy.Content content = DependencyTypePolicy.resolveContent(type);
            boolean supportedSomewhere = EXPECTED.get(type).values().stream().anyMatch(disposition -> disposition != Disposition.UNSUPPORTED);
            assertEquals(supportedSomewhere, content != null, type + ": a supported type has a content rule and an unsupported one has none");
        }
        assertEquals(DependencyTypePolicy.Content.EXAMINED, DependencyTypePolicy.resolveContent(ResourceTypes.APPLICATION));
        assertEquals(DependencyTypePolicy.Content.LEAF, DependencyTypePolicy.resolveContent(ResourceTypes.FILE));
        assertNull(DependencyTypePolicy.resolveContent(ResourceTypes.TOOL_SET), "a ToolSet is not listed");
    }

    @Test
    public void testEveryExaminedTypeHasItsEntity() {
        List<ResourceTypes> examined = Arrays.stream(ResourceTypes.values())
                .filter(type -> DependencyTypePolicy.resolveContent(type) == DependencyTypePolicy.Content.EXAMINED).toList();

        assertFalse(examined.isEmpty());
        assertEquals(examined, DependencyTypePolicy.listExaminedTypes());
        for (ResourceTypes type : examined) {
            assertTrue(Deployment.class.isAssignableFrom(DependencyTypePolicy.resolveEntityClass(type)), type + " has no entity class");
            assertNull(DependencyTypePolicy.findDeployment(new Config(), type, "absent"), type + " has no way to find its entities");
        }
    }

    @Test
    public void testTypeThatIsNotExaminedHasNoEntity() {
        for (ResourceTypes type : ResourceTypes.values()) {
            if (DependencyTypePolicy.resolveContent(type) != DependencyTypePolicy.Content.EXAMINED) {
                assertThrows(IllegalArgumentException.class, () -> DependencyTypePolicy.resolveEntityClass(type), type.toString());
                assertNull(DependencyTypePolicy.findDeployment(new Config(), type, "absent"), type.toString());
            }
        }
    }

    @Test
    public void testFindDeploymentReadsTheEntityOfTheTypeFromTheConfig() {
        Config config = new Config();
        Application application = new Application();
        Model model = new Model();
        Interceptor interceptor = new Interceptor();
        config.setApplications(Map.of("n", application));
        config.setModels(Map.of("n", model));
        config.setInterceptors(Map.of("n", interceptor));

        assertSame(application, DependencyTypePolicy.findDeployment(config, ResourceTypes.APPLICATION, "n"));
        assertSame(model, DependencyTypePolicy.findDeployment(config, ResourceTypes.MODEL, "n"));
        assertSame(interceptor, DependencyTypePolicy.findDeployment(config, ResourceTypes.INTERCEPTOR, "n"));
        assertEquals(Application.class, DependencyTypePolicy.resolveEntityClass(ResourceTypes.APPLICATION));
        assertEquals(Model.class, DependencyTypePolicy.resolveEntityClass(ResourceTypes.MODEL));
        assertEquals(Interceptor.class, DependencyTypePolicy.resolveEntityClass(ResourceTypes.INTERCEPTOR));
    }
}
