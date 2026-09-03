package com.epam.aidial.core.server.util;

import com.epam.aidial.core.storage.resource.LegacyStorageLayout;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.resource.StorageLayouts;
import com.epam.aidial.core.storage.resource.TenantRootedStorageLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every descriptor the platform builds against a system bucket has to compose a path under the tenant-rooted
 * layout, not just the ones the resource API can reach.
 *
 * <p>These paths are built off the request path, so an unmapped location does not surface as a failed
 * request — it throws inside a background task and takes the subsystem out silently.
 */
public class SystemBucketLayoutTest {

    private static final String TENANT = "acme";

    @BeforeEach
    public void useTenantRootedLayout() {
        StorageLayouts.useLayout(new TenantRootedStorageLayout(TENANT));
    }

    @AfterEach
    public void restoreLegacyLayout() {
        StorageLayouts.useLayout(LegacyStorageLayout.INSTANCE);
    }

    @Test
    public void testResponseMappingPath() {
        ResourceDescriptor descriptor = ResponseIdUtil.getResponseMappingDescriptor("dial_gpt-4_abc123");

        assertEquals(".system/response_mappings/.response_mappings/gpt-4/abc123", descriptor.getAbsoluteFilePath());
        assertEquals("response_mappings/response_mappings/gpt-4/abc123", descriptor.getStableFilePath());
    }

    @Test
    public void testBackgroundJobPath() {
        ResourceDescriptor descriptor = ResponseIdUtil.getBackgroundJobDescriptor("job-1");

        assertEquals(".system/background_jobs/.background_jobs/job-1", descriptor.getAbsoluteFilePath());
    }

    /**
     * The scheduler scans the whole bucket, and cost accounting deletes by trace id — both address the folder
     * itself, which is a separate composition path from an item.
     */
    @Test
    public void testBackgroundJobRootFolderPath() {
        ResourceDescriptor root = ResponseIdUtil.getBackgroundJobDescriptor(null);

        assertEquals(".system/background_jobs/.background_jobs/", root.getAbsoluteFilePath());
    }

    @Test
    public void testApiKeyDataPath() {
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(ResourceTypes.API_KEY_DATA,
                ResourceDescriptor.API_KEY_DATA_BUCKET, ResourceDescriptor.API_KEY_DATA_LOCATION, "some-key");

        assertEquals(".system/api_key_data/.api_key_data/some-key", descriptor.getAbsoluteFilePath());
    }

    @Test
    public void testDeploymentCostStatsPath() {
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(ResourceTypes.DEPLOYMENT_COST_STATS,
                ResourceDescriptor.DEPLOYMENT_COST_STATS_BUCKET, ResourceDescriptor.DEPLOYMENT_COST_STATS_LOCATION,
                "trace-id");

        assertEquals(".system/deployment_cost_stats/.deployment_cost_stats/trace-id", descriptor.getAbsoluteFilePath());
    }

}
