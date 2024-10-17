package com.epam.aidial.core.function;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.security.EncryptionService;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.UrlUtil;
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
    public static ResourceDescription fromAnyUrl(String url, EncryptionService encryption) {
        if (url == null || UrlUtil.isAbsoluteUrl(url) || UrlUtil.isDataUrl(url)) {
            // skipping public resources and Data URLs
            return null;
        }

        return ResourceDescription.fromAnyUrl(url, encryption);
    }
}
