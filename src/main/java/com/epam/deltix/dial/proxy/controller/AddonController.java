package com.epam.deltix.dial.proxy.controller;

import com.epam.deltix.dial.proxy.ProxyContext;
import com.epam.deltix.dial.proxy.config.Addon;
import com.epam.deltix.dial.proxy.config.Config;
import com.epam.deltix.dial.proxy.data.AddonData;
import com.epam.deltix.dial.proxy.data.ListData;
import com.epam.deltix.dial.proxy.util.HttpStatus;
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
        return context.respond(HttpStatus.OK, data);
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

        return context.respond(HttpStatus.OK, list);
    }

    private static AddonData createAddon(Addon addon) {
        AddonData data = new AddonData();
        data.setId(addon.getName());
        data.setAddon(addon.getName());
        return data;
    }
}