package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.LegacyStorageLayout;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.resource.StorageLayouts;
import com.epam.aidial.core.storage.resource.TenantRootedStorageLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code ApplicationService} keys a function app's deployment folder as a synthesized sub-bucket of the
 * owner's location, so these shapes never pass through the bucket builder. Each of them has to compose a
 * physical path under the tenant-rooted layout — the public one is how every deploy of a public function
 * app came to throw under it.
 */
public class ApplicationDeploymentLayoutTest {

    @AfterEach
    public void restoreLegacyLayout() {
        StorageLayouts.useLayout(LegacyStorageLayout.INSTANCE);
    }

    @Test
    public void testPublicDeploymentFolder() {
        StorageLayouts.useLayout(new TenantRootedStorageLayout("acme"));

        ResourceDescriptor folder = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.FILE, "bucket", "public/deployments/fn-1/", null);

        assertEquals(".org/acme/deployments/fn-1/.files/", folder.getAbsoluteFilePath());
        assertEquals("public/deployments/fn-1/files/", folder.getStableFilePath());
    }

    @Test
    public void testPrivateDeploymentFolder() {
        StorageLayouts.useLayout(new TenantRootedStorageLayout("acme"));

        ResourceDescriptor folder = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.FILE, "bucket", "Users/u1/deployments/fn-1/", null);

        assertEquals(".org/acme/.users/u1/deployments/fn-1/.files/", folder.getAbsoluteFilePath());
    }

    @Test
    public void testReviewDeploymentFolder() {
        StorageLayouts.useLayout(new TenantRootedStorageLayout("acme"));

        ResourceDescriptor folder = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.FILE, "bucket", "Users/u1/publications/p1/deployments/fn-1/", null);

        assertEquals(".org/acme/.users/u1/publications/p1/deployments/fn-1/.files/", folder.getAbsoluteFilePath());
    }
}
