package com.epam.aidial.core.server.data.config.apply;

import com.epam.aidial.core.config.Application;

public record AdminApplicationManifest(String kind, String name, Application spec) implements AdminTypedManifest {
}
