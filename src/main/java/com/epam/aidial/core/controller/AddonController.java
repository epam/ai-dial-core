package com.epam.aidial.core.controller;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Addon;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.data.AddonData;
import com.epam.aidial.core.data.ListData;
import com.epam.aidial.core.util.HttpStatus;
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

        if (!DeploymentController.hasAccess(context, addon)) {
            return context.respond(HttpStatus.FORBIDDEN);
        }

        AddonData data = createAddon(addon);
        context.respond(HttpStatus.OK, data);
        return Future.succeededFuture();
    }

    public Future<?> getAddons() {
        Config config = context.getConfig();
        List<AddonData> addons = new ArrayList<>();

        for (Addon addon : config.getAddons().values()) {
            if (DeploymentController.hasAccess(context, addon)) {
                AddonData data = createAddon(addon);
                addons.add(data);
            }
        }

        ListData<AddonData> list = new ListData<>();
        list.setData(addons);

        context.respond(HttpStatus.OK, list);
        return Future.succeededFuture();
    }

    private static AddonData createAddon(Addon addon) {
        AddonData data = new AddonData();
        data.setId(addon.getName());
        data.setAddon(addon.getName());
        data.setDisplayName(addon.getDisplayName());
        data.setDisplayVersion(addon.getDisplayVersion());
        data.setIconUrl(addon.getIconUrl());
        data.setDescription(addon.getDescription());
        return data;
    }
}