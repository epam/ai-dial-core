package com.epam.aidial.core.server.data.config.manifest;

import com.epam.aidial.core.config.Key;

public record AdminKeyManifest(String kind, String name, Key spec) implements AdminTypedManifest {
}
