package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
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
 * {@code ApplicationService} keys a function app's deployment folder as a synthesized sub-bucket of the
 * owner's location, so these shapes never pass through the bucket builder. Each of them has to compose a
 * physical path under the tenant-rooted layout, and the locations are composed by the service itself —
 * a literal here could drift from what production synthesizes.
 */
public class ApplicationDeploymentLayoutTest {

    @BeforeEach
    public void useTenantRootedLayout() {
        StorageLayouts.useLayout(new TenantRootedStorageLayout("acme"));
    }

    @AfterEach
    public void restoreLegacyLayout() {
        StorageLayouts.useLayout(LegacyStorageLayout.INSTANCE);
    }

    @Test
    public void testPublicDeploymentFolder() {
        ResourceDescriptor folder = deploymentFolder(ResourceDescriptor.PUBLIC_LOCATION);

        assertEquals(".org/acme/deployments/fn-1/.files/", folder.getAbsoluteFilePath());
        assertEquals("public/deployments/fn-1/files/", folder.getStableFilePath());
    }

    @Test
    public void testPrivateDeploymentFolder() {
        ResourceDescriptor folder = deploymentFolder(ResourceDescriptor.USERS_LOCATION_PREFIX + "u1/");

        assertEquals(".org/acme/.users/u1/deployments/fn-1/.files/", folder.getAbsoluteFilePath());
    }

    @Test
    public void testReviewDeploymentFolder() {
        ResourceDescriptor folder = deploymentFolder(ResourceDescriptor.USERS_LOCATION_PREFIX + "u1/publications/p1/");

        assertEquals(".org/acme/.users/u1/publications/p1/deployments/fn-1/.files/", folder.getAbsoluteFilePath());
    }

    private static ResourceDescriptor deploymentFolder(String ownerLocation) {
        String location = ApplicationService.deploymentFolderLocation(ownerLocation, "fn-1");
        return ResourceDescriptorFactory.fromDecoded(ResourceTypes.FILE, "bucket", location, null);
    }
}
