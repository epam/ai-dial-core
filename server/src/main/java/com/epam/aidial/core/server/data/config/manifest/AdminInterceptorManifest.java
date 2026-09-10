package com.epam.aidial.core.server.data.config.manifest;

import com.epam.aidial.core.config.Interceptor;

public record AdminInterceptorManifest(String kind, String name, Interceptor spec) implements AdminTypedManifest {
}
