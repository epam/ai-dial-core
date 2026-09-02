package com.epam.aidial.core.server.util;

import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.token.TokenStatsTracker;
import com.epam.aidial.core.storage.resource.LegacyStorageLayout;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.resource.StorageLayouts;
import com.epam.aidial.core.storage.resource.TenantRootedStorageLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every descriptor the platform builds against a system bucket has to compose a path under the tenant-rooted
 * layout, not just the ones the resource API can reach.
 *
 * <p>These paths are built off the request path — cost accounting runs per span, the job scheduler on a timer,
 * response mappings on every completion — so an unmapped location does not surface as a failed request. It
 * throws inside a background task and takes the subsystem out silently, which is how all three of these came
 * to be broken under the tenant-rooted layout without a single test going red.
 */
public class SystemBucketLayoutTest {

    private static final String TENANT = "acme";

    @AfterEach
    public void restoreLegacyLayout() {
        StorageLayouts.useLayout(LegacyStorageLayout.INSTANCE);
    }

    @Test
    public void testResponseMappingPath() {
        StorageLayouts.useLayout(new TenantRootedStorageLayout(TENANT));

        ResourceDescriptor descriptor = ResponseIdUtil.getResponseMappingDescriptor("dial_gpt-4_abc123");

        assertEquals(".system/response_mappings/.response_mappings/gpt-4/abc123", descriptor.getAbsoluteFilePath());
        assertEquals("response_mappings/response_mappings/gpt-4/abc123", descriptor.getStableFilePath());
    }

    @Test
    public void testBackgroundJobPath() {
        StorageLayouts.useLayout(new TenantRootedStorageLayout(TENANT));

        ResourceDescriptor descriptor = ResponseIdUtil.getBackgroundJobDescriptor("job-1");

        assertEquals(".system/background_jobs/.background_jobs/job-1", descriptor.getAbsoluteFilePath());
    }

    /**
     * The scheduler scans the whole bucket, and cost accounting deletes by trace id — both address the folder
     * itself, which is a separate composition path from an item.
     */
    @Test
    public void testBackgroundJobRootFolderPath() {
        StorageLayouts.useLayout(new TenantRootedStorageLayout(TENANT));

        ResourceDescriptor root = ResponseIdUtil.getBackgroundJobDescriptor(null);

        assertEquals(".system/background_jobs/.background_jobs/", root.getAbsoluteFilePath());
    }

    // Per-request keys are how an application caller is represented, so this one breaks four of the eleven
    // permission rules rather than a background task.
    @Test
    public void testApiKeyDataPath() {
        StorageLayouts.useLayout(new TenantRootedStorageLayout(TENANT));

        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(ResourceTypes.API_KEY_DATA,
                ApiKeyStore.API_KEY_DATA_BUCKET, ApiKeyStore.API_KEY_DATA_LOCATION, "some-key");

        assertEquals(".system/api_key_data/.api_key_data/some-key", descriptor.getAbsoluteFilePath());
    }

    @Test
    public void testDeploymentCostStatsPath() {
        StorageLayouts.useLayout(new TenantRootedStorageLayout(TENANT));

        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(ResourceTypes.DEPLOYMENT_COST_STATS,
                TokenStatsTracker.DEPLOYMENT_COST_STATS_BUCKET, TokenStatsTracker.DEPLOYMENT_COST_STATS_LOCATION,
                "trace-id");

        assertEquals(".system/deployment_cost_stats/.deployment_cost_stats/trace-id", descriptor.getAbsoluteFilePath());
    }

    /**
     * A system bucket added elsewhere and not registered in {@link ResourceDescriptor#SYSTEM_LOCATIONS} is
     * exactly the defect this class exists for, so pin that the locations these utilities use are the
     * registered ones rather than parallel literals.
     */
    @Test
    public void testCallersUseRegisteredLocations() {
        for (String location : List.of(
                ResponseIdUtil.RESPONSE_MAPPINGS_BUCKET_LOCATION,
                ResponseIdUtil.BACKGROUND_JOB_BUCKET_LOCATION,
                TokenStatsTracker.DEPLOYMENT_COST_STATS_LOCATION,
                ApiKeyStore.API_KEY_DATA_LOCATION)) {
            assertTrue(ResourceDescriptor.SYSTEM_LOCATIONS.contains(location),
                    () -> location + " is not registered in ResourceDescriptor.SYSTEM_LOCATIONS");
        }
    }
}
