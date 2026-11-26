package com.epam.aidial.core.server.service.dependency;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.service.dependency.DependencyAccessPolicy.ConfigSnapshot;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The policy of an explicit admin: {@code public/}, {@code platform/} and the file configuration, the right to read in {@code public/}
 * as a guard, no check of the roles of a deployment, and every other location unsupported. Then the policy of any other caller: the right
 * to read in the stores of the user, the roles of a deployment of the configuration, and one lookup per level.
 */
public class DependencyAccessPolicyTest {

    private static final DependencyAccessPolicy READS_PUBLIC = new AdminAccessPolicy(resource -> true);
    private static final ConfigSnapshot NO_CONFIG = new ConfigSnapshot(new Config(), null);

    private static ResourceDescriptor descriptor(ResourceTypes type, String bucket, String location, String path) {
        return ResourceDescriptorFactory.fromDecoded(type, bucket, location, path);
    }

    private static ResourceDescriptor platform(ResourceTypes type, String name) {
        return descriptor(type, "platform", "platform/", name);
    }

    private static ResourceDescriptor publicResource(ResourceTypes type, String path) {
        return descriptor(type, "public", "public/", path);
    }

    private static Set<ResourceDescriptor> listed(DependencyAccessPolicy policy, ResourceDescriptor... targets) {
        return listed(policy, NO_CONFIG, targets);
    }

    private static Set<ResourceDescriptor> listed(DependencyAccessPolicy policy, ConfigSnapshot configuration, ResourceDescriptor... targets) {
        return policy.selectListable(new LinkedHashSet<>(List.of(targets)), configuration);
    }

    @Test
    public void testPlatformDependencyIsListed() {
        ResourceDescriptor model = platform(ResourceTypes.MODEL, "gpt-4o");
        ResourceDescriptor interceptor = platform(ResourceTypes.INTERCEPTOR, "audit");
        ResourceDescriptor application = platform(ResourceTypes.APPLICATION, "helper");
        ResourceDescriptor schema = platform(ResourceTypes.APP_TYPE_SCHEMA, "s");

        assertEquals(Set.of(model, interceptor, application, schema), listed(READS_PUBLIC, model, interceptor, application, schema));
    }

    @Test
    public void testRolesOfDeploymentAreNotCheckedForAdmin() {
        // the policy knows no roles: a platform deployment is listed whoever may call it
        ResourceDescriptor restricted = platform(ResourceTypes.MODEL, "internal-llm");

        assertEquals(Set.of(restricted), listed(READS_PUBLIC, restricted));
    }

    @Test
    public void testEntityOfTheFileConfigurationIsListedAndItsUrlIsNotExposed() {
        ResourceDescriptor entity = FileConfigEntities.newDescriptor(ResourceTypes.MODEL, "fm");

        assertEquals(Set.of(entity), listed(READS_PUBLIC, entity));
        assertFalse(READS_PUBLIC.isUrlExposed(entity));
        assertTrue(READS_PUBLIC.isUrlExposed(platform(ResourceTypes.MODEL, "m")));
    }

    @Test
    public void testPublicResourceIsListedOnlyWhenTheCallerMayReadIt() {
        ResourceDescriptor readable = publicResource(ResourceTypes.FILE, "a/icon.png");
        ResourceDescriptor hidden = publicResource(ResourceTypes.PROMPT, "secret");
        Predicate<ResourceDescriptor> reads = resource -> !"secret".equals(resource.getName());
        DependencyAccessPolicy policy = new AdminAccessPolicy(reads);

        assertEquals(Set.of(readable), listed(policy, readable, hidden));
    }

    @Test
    public void testUserBucketAndOtherLocationsAreNotListed() {
        ResourceDescriptor userFile = descriptor(ResourceTypes.FILE, "bucketToken", "Users/someone/", "a.txt");
        ResourceDescriptor userApplication = descriptor(ResourceTypes.APPLICATION, "bucketToken", "Users/someone/", "app");
        ResourceDescriptor review = descriptor(ResourceTypes.FILE, "reviewToken", "public/publications/p1/", "a.txt");

        assertEquals(Set.of(), listed(READS_PUBLIC, userFile, userApplication, review));
    }

    @Test
    public void testTypeOutsideTheTableAndFolderAreNotListed() {
        ResourceDescriptor toolSet = platform(ResourceTypes.TOOL_SET, "t");
        ResourceDescriptor router = platform(ResourceTypes.ROUTER, "r");
        ResourceDescriptor folder = publicResource(ResourceTypes.FILE, "folder/");

        assertEquals(Set.of(), listed(READS_PUBLIC, toolSet, router, folder));
    }

    @Test
    public void testRootMustBeAnApplicationOfPublicOrPlatformThatTheCallerMayRead() {
        DependencyAccessPolicy policy = new AdminAccessPolicy(resource -> !"hidden".equals(resource.getName()));

        assertTrue(policy.isRootAllowed(platform(ResourceTypes.APPLICATION, "a")));
        assertTrue(policy.isRootAllowed(publicResource(ResourceTypes.APPLICATION, "team/a__1.0")));
        assertFalse(policy.isRootAllowed(publicResource(ResourceTypes.APPLICATION, "hidden")), "a public root that the caller may not read");
        assertFalse(policy.isRootAllowed(platform(ResourceTypes.MODEL, "m")), "only an Application is a root");
        assertFalse(policy.isRootAllowed(descriptor(ResourceTypes.APPLICATION, "bucketToken", "Users/someone/", "a")), "a user bucket");
        assertFalse(policy.isRootAllowed(publicResource(ResourceTypes.APPLICATION, "folder/")), "a folder");
    }

    private static final String OWN = "Users/me/";
    private static final String OTHER = "Users/other/";

    /** What the initiator policy asks of the access service and of the configuration. */
    private static final class InitiatorSetup {
        private final Set<String> readable = new HashSet<>();
        private final AtomicInteger lookups = new AtomicInteger();
        private final AtomicInteger lookedUp = new AtomicInteger();
        private final Config merged = new Config();
        private final Config file = new Config();

        private InitiatorSetup() {
            for (Config config : List.of(merged, file)) {
                config.setModels(new HashMap<>());
                config.setApplications(new HashMap<>());
                config.setInterceptors(new HashMap<>());
            }
        }

        private InitiatorAccessPolicy policy(String... roles) {
            return new InitiatorAccessPolicy(targets -> {
                lookups.incrementAndGet();
                lookedUp.addAndGet(targets.size());
                Map<ResourceDescriptor, Set<ResourceAccessType>> permissions = new HashMap<>();
                targets.forEach(target -> permissions.put(target, readable.contains(target.getName()) ? ResourceAccessType.READ_ONLY : Set.of()));
                return permissions;
            }, List.of(roles));
        }

        /** The configuration that a walk would hand to the policy. */
        private ConfigSnapshot snapshot() {
            return new ConfigSnapshot(merged, file);
        }
    }

    /** No argument: no roles at all, which admits every caller. */
    private static Model modelWithRoles(String... roles) {
        Model model = new Model();
        model.setUserRoles(roles.length == 0 ? null : Set.of(roles));
        return model;
    }

    @Test
    public void testInitiatorListsOnlyWhatTheCallerMayRead() {
        InitiatorSetup setup = new InitiatorSetup();
        setup.readable.addAll(Set.of("mine.png", "open.png", "app"));
        ResourceDescriptor own = descriptor(ResourceTypes.FILE, "tokenMe", OWN, "mine.png");
        ResourceDescriptor others = descriptor(ResourceTypes.FILE, "tokenOther", OTHER, "theirs.png");
        ResourceDescriptor open = publicResource(ResourceTypes.FILE, "open/open.png");
        ResourceDescriptor closed = publicResource(ResourceTypes.FILE, "closed/closed.png");
        ResourceDescriptor application = descriptor(ResourceTypes.APPLICATION, "tokenOther", OTHER, "app");

        assertEquals(Set.of(own, open, application), listed(setup.policy(), own, others, open, closed, application));
        assertEquals(1, setup.lookups.get(), "one lookup for the whole level");
        assertEquals(5, setup.lookedUp.get());
    }

    @Test
    public void testInitiatorAsksNothingForConfigurationEntities() {
        InitiatorSetup setup = new InitiatorSetup();
        setup.merged.getModels().put("m", modelWithRoles());
        ResourceDescriptor model = platform(ResourceTypes.MODEL, "m");
        ResourceDescriptor interceptor = platform(ResourceTypes.INTERCEPTOR, "i");

        assertEquals(Set.of(model, interceptor), listed(setup.policy(), setup.snapshot(), model, interceptor));
        assertEquals(0, setup.lookups.get());
    }

    @Test
    public void testDeploymentOfTheConfigurationNeedsRoleThatAdmitsTheCaller() {
        InitiatorSetup setup = new InitiatorSetup();
        setup.merged.getModels().put("open", modelWithRoles());
        setup.merged.getModels().put("power", modelWithRoles("power-user"));
        Model nobody = new Model();
        nobody.setUserRoles(Set.of());
        setup.merged.getModels().put("nobody", nobody);
        setup.file.getModels().put("fileOnly", modelWithRoles("user"));
        Application restricted = new Application();
        restricted.setUserRoles(Set.of("power-user"));
        setup.merged.getApplications().put("restricted", restricted);
        ResourceDescriptor open = platform(ResourceTypes.MODEL, "open");
        ResourceDescriptor power = platform(ResourceTypes.MODEL, "power");
        ResourceDescriptor empty = platform(ResourceTypes.MODEL, "nobody");
        ResourceDescriptor fileOnly = FileConfigEntities.newDescriptor(ResourceTypes.MODEL, "fileOnly");
        ResourceDescriptor application = platform(ResourceTypes.APPLICATION, "restricted");
        ResourceDescriptor unknown = platform(ResourceTypes.MODEL, "unknown");
        Set<ResourceDescriptor> all = new LinkedHashSet<>(List.of(open, power, empty, fileOnly, application, unknown));

        assertEquals(Set.of(open, fileOnly), setup.policy("user").selectListable(all, setup.snapshot()),
                "no role of the caller, empty roles, or no entity");
        assertEquals(Set.of(open, power, fileOnly, application), setup.policy("user", "power-user").selectListable(all, setup.snapshot()));
    }

    @Test
    public void testInitiatorDecidesOnTheGivenSnapshotNotOnLaterConfiguration() {
        InitiatorSetup setup = new InitiatorSetup();
        setup.merged.getModels().put("m", modelWithRoles("power-user"));
        ConfigSnapshot admitting = setup.snapshot();
        Config later = new Config();
        later.setModels(new HashMap<>(Map.of("m", modelWithRoles("someone-else"))));
        ConfigSnapshot refusing = new ConfigSnapshot(later, null);
        ResourceDescriptor model = platform(ResourceTypes.MODEL, "m");
        InitiatorAccessPolicy policy = setup.policy("power-user");

        assertEquals(Set.of(model), listed(policy, admitting, model));
        assertEquals(Set.of(), listed(policy, refusing, model), "the same policy, another publication: the roles of that one decide");
    }

    @Test
    public void testInitiatorUrlIsExposedExceptForDeploymentsOfTheConfiguration() {
        InitiatorAccessPolicy policy = new InitiatorSetup().policy();

        assertFalse(policy.isUrlExposed(platform(ResourceTypes.MODEL, "m")));
        assertFalse(policy.isUrlExposed(platform(ResourceTypes.APPLICATION, "a")));
        assertFalse(policy.isUrlExposed(FileConfigEntities.newDescriptor(ResourceTypes.INTERCEPTOR, "i")));
        assertTrue(policy.isUrlExposed(platform(ResourceTypes.INTERCEPTOR, "i")));
        assertTrue(policy.isUrlExposed(platform(ResourceTypes.APP_TYPE_SCHEMA, "s")));
        assertTrue(policy.isUrlExposed(publicResource(ResourceTypes.APPLICATION, "a")));
        assertTrue(policy.isUrlExposed(descriptor(ResourceTypes.FILE, "tokenMe", OWN, "a.txt")));
    }

    @Test
    public void testInitiatorSupportsTheStoresOfTheUserAndNothingElse() {
        InitiatorAccessPolicy policy = new InitiatorSetup().policy();

        assertTrue(policy.isSupported(descriptor(ResourceTypes.APPLICATION, "tokenMe", OWN, "a")));
        assertTrue(policy.isSupported(descriptor(ResourceTypes.PROMPT, "tokenOther", OTHER, "p")));
        assertTrue(policy.isSupported(descriptor(ResourceTypes.FILE, "tokenKey", "Keys/project/", "f")));
        assertFalse(policy.isSupported(descriptor(ResourceTypes.MODEL, "tokenMe", OWN, "m")), "a Model is not stored in a user bucket");
        assertFalse(policy.isSupported(descriptor(ResourceTypes.FILE, "reviewToken", "public/publications/p1/", "a.txt")), "a review bucket");
        assertFalse(policy.isSupported(descriptor(ResourceTypes.CONVERSATION, "tokenMe", OWN, "c")), "a type outside the table");
        assertFalse(policy.isSupported(descriptor(ResourceTypes.FILE, "tokenMe", OWN, "folder/")), "a folder");
        assertFalse(policy.isSupported(platform(ResourceTypes.FILE, "f")), "platform has no files");
    }

    @Test
    public void testInitiatorRootIsOwnSharedOrPublicAndReadable() {
        InitiatorSetup setup = new InitiatorSetup();
        setup.readable.addAll(Set.of("mine", "shared", "open"));
        InitiatorAccessPolicy policy = setup.policy();

        assertTrue(policy.isRootAllowed(descriptor(ResourceTypes.APPLICATION, "tokenMe", OWN, "mine")));
        assertTrue(policy.isRootAllowed(descriptor(ResourceTypes.APPLICATION, "tokenOther", OTHER, "shared")));
        assertTrue(policy.isRootAllowed(publicResource(ResourceTypes.APPLICATION, "open")));
        assertFalse(policy.isRootAllowed(descriptor(ResourceTypes.APPLICATION, "tokenOther", OTHER, "theirs")), "not readable");
        assertFalse(policy.isRootAllowed(publicResource(ResourceTypes.APPLICATION, "closed")), "a public folder that does not allow the caller");
        assertFalse(policy.isRootAllowed(platform(ResourceTypes.APPLICATION, "mine")), "platform is never a root of a caller");
        assertFalse(policy.isRootAllowed(FileConfigEntities.newDescriptor(ResourceTypes.APPLICATION, "mine")), "the file configuration is not a root");
        assertFalse(policy.isRootAllowed(descriptor(ResourceTypes.FILE, "tokenMe", OWN, "mine")), "only an Application is a root");
    }

    @Test
    public void testInitiatorInterceptorOfTheFileConfigurationIsListed() {
        InitiatorSetup setup = new InitiatorSetup();
        setup.file.getInterceptors().put("audit", new Interceptor());
        ResourceDescriptor interceptor = FileConfigEntities.newDescriptor(ResourceTypes.INTERCEPTOR, "audit");

        assertEquals(Set.of(interceptor), listed(setup.policy(), setup.snapshot(), interceptor));
    }
}
