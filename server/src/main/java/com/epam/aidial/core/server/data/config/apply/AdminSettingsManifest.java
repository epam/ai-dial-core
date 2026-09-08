package com.epam.aidial.core.server.data.config.apply;

import com.epam.aidial.core.config.GlobalSettings;

public record AdminSettingsManifest(String kind, String name, GlobalSettings spec) implements AdminTypedManifest {
}
