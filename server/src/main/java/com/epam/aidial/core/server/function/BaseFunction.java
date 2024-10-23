package com.epam.aidial.core.server.function;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.resource.ResourceDescriptor;
import com.epam.aidial.core.server.resource.ResourceDescriptorFactory;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.UrlUtil;
import lombok.SneakyThrows;

import java.util.function.Function;

public abstract class BaseFunction<T, R> implements Function<T, R> {
    protected final Proxy proxy;
    protected final ProxyContext context;

    public BaseFunction(Proxy proxy, ProxyContext context) {
        this.proxy = proxy;
        this.context = context;
    }

    @SneakyThrows
    public static ResourceDescriptor fromAnyUrl(String url, EncryptionService encryption) {
        if (url == null || UrlUtil.isAbsoluteUrl(url) || UrlUtil.isDataUrl(url)) {
            // skipping public resources and Data URLs
            return null;
        }

        return ResourceDescriptorFactory.fromAnyUrl(url, encryption);
    }
}
