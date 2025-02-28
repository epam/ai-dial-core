package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Addon;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.AddonData;
import com.epam.aidial.core.server.data.ListData;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
public class AddonController {

    private final ProxyContext context;

    public Future<?> getAddon(String addonId) {
        Config config = context.getConfig();
        Addon addon = config.getAddons().get(addonId);

        if (addon == null) {
            return context.respond(HttpStatus.NOT_FOUND);
        }

        if (!addon.hasAccess(context.getUserRoles())) {
            return context.respond(HttpStatus.FORBIDDEN);
        }

        AddonData data = createAddon(addon);
        return context.respond(HttpStatus.OK, data);
    }

    public Future<?> getAddons() {
        Config config = context.getConfig();
        List<AddonData> addons = new ArrayList<>();

        for (Addon addon : config.getAddons().values()) {
            if (addon.hasAccess(context.getUserRoles())) {
                AddonData data = createAddon(addon);
                addons.add(data);
            }
        }

        ListData<AddonData> list = new ListData<>();
        list.setData(addons);

        return context.respond(HttpStatus.OK, list);
    }

    private static AddonData createAddon(Addon addon) {
        AddonData data = new AddonData();
        data.setId(addon.getName());
        data.setAddon(addon.getName());
        data.setDisplayName(addon.getDisplayName());
        data.setDisplayVersion(addon.getDisplayVersion());
        data.setIconUrl(addon.getIconUrl());
        data.setDescription(addon.getDescription());
        data.setReference(addon.getName());
        data.setDescriptionKeywords(addon.getDescriptionKeywords());
        if (addon.getAuthor() != null) {
            data.setOwner(addon.getAuthor());
        }
        if (addon.getCreatedAt() != null) {
            data.setCreatedAt(addon.getCreatedAt());
        }
        if (addon.getUpdatedAt() != null) {
            data.setUpdatedAt(addon.getUpdatedAt());
        }
        return data;
    }
}