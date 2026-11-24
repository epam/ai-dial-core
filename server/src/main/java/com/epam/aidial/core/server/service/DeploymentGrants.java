package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.permission.PerRequestSharedData;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.UrlUtil;
import lombok.experimental.UtilityClass;

import javax.annotation.Nullable;

/**
 * The right to call a deployment, held as {@code READ} on its url in a per-request key's
 * {@code perRequestSharedResources}: the url of a resource deployment is its name, that of a config deployment the
 * platform-bucket url the config store keeps it under, {@code <type>/platform/<name>}.
 */
@UtilityClass
public final class DeploymentGrants {

    /**
     * The url a grant on the deployment {@code name} is held under: {@code <type>/platform/<name>} when the config has a
     * deployment of that name, and otherwise the resource url, encoded: a resource deployment is named by its encoded
     * url, and a request may name it by its decoded url (the deployments-POST path does), so both give the same url.
     * Null for a null name.
     */
    @Nullable
    public String urlOf(Config config, @Nullable String name) {
        if (name == null) {
            return null;
        }
        ResourceTypes type = configType(config, name);
        if (type == null) {
            return UrlUtil.encodePath(UrlUtil.decodePath(name, false));
        }
        return ResourceDescriptorFactory.fromDecoded(type, ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION, name).getUrl();
    }

    /**
     * Whether {@code key}'s {@code perRequestSharedResources} hold {@code READ} on exactly {@code url}: a folder entry
     * above it does not count, so that no grant opens every deployment of a type.
     */
    public boolean isGranted(ApiKeyData key, String url) {
        PerRequestSharedData data = key.getPerRequestSharedResources().get(url);
        return data != null && data.permissions().contains(ResourceAccessType.READ);
    }

    @Nullable
    private ResourceTypes configType(Config config, String name) {
        if (config.getModels().containsKey(name)) {
            return ResourceTypes.MODEL;
        }
        if (config.getApplications().containsKey(name)) {
            return ResourceTypes.APPLICATION;
        }
        if (config.getToolsets().containsKey(name)) {
            return ResourceTypes.TOOL_SET;
        }
        if (config.getRouters().containsKey(name)) {
            return ResourceTypes.ROUTER;
        }
        if (config.getInterceptors().containsKey(name)) {
            return ResourceTypes.INTERCEPTOR;
        }
        if (config.getAliases().containsKey(name)) {
            return ResourceTypes.ALIAS;
        }
        return null;
    }
}
