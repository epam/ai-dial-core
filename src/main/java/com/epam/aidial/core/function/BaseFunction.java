package com.epam.aidial.core.function;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.security.EncryptionService;
import com.epam.aidial.core.storage.ResourceDescription;
import lombok.SneakyThrows;

import java.net.URI;
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
        if (url == null) {
            return null;
        }
        URI uri = new URI(url);
        if (uri.isAbsolute()) {
            // skip public resource
            return null;
        }
        return ResourceDescription.fromAnyUrl(url, encryption);
    }
}
