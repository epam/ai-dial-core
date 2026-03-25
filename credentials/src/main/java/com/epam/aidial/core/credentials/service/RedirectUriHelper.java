package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.ResourceAuthSettings;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;

@UtilityClass
public class RedirectUriHelper {

    /**
     * Builds the effective list of allowed redirect URIs by combining the global allowed list
     * with the toolset's own redirect URI (if present and not already included).
     */
    public List<String> collectAllowedRedirectUris(List<String> globalAllowedUris, ResourceAuthSettings resourceAuthSettings) {
        List<String> uris = new ArrayList<>(globalAllowedUris);
        if (resourceAuthSettings.getRedirectUri() != null && !uris.contains(resourceAuthSettings.getRedirectUri())) {
            uris.add(resourceAuthSettings.getRedirectUri());
        }
        return uris;
    }
}
