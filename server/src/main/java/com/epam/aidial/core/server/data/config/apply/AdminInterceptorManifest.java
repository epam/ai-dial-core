package com.epam.aidial.core.server.data.config.apply;

import com.epam.aidial.core.config.Interceptor;

public record AdminInterceptorManifest(String kind, String name, Interceptor spec) implements AdminTypedManifest {
}
