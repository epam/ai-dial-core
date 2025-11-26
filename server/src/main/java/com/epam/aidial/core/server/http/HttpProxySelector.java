package com.epam.aidial.core.server.http;

import io.vertx.core.net.ProxyOptions;
import io.vertx.core.net.impl.ProxyFilter;
import io.vertx.core.net.impl.SocketAddressImpl;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

@Slf4j
public class HttpProxySelector extends ProxySelector {

    private final List<Proxy> httpProxies;
    private final ProxyFilter proxyFilter;

    public HttpProxySelector(ProxyOptions proxyOptions, @Nullable List<String> nonProxyHosts) {
        Objects.requireNonNull(proxyOptions, "Proxy must be defined");
        String host = proxyOptions.getHost();
        int port = proxyOptions.getPort();
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
        httpProxies = List.of(proxy);
        proxyFilter = nonProxyHosts == null ? null : ProxyFilter.nonProxyHosts(nonProxyHosts);
    }

    @Override
    public List<Proxy> select(URI uri) {
        if (uri == null) {
            throw new IllegalArgumentException("URI cannot be null.");
        }

        if (canProxy(uri)) {
            // For HTTP/HTTPS, return the defined HTTP proxies
            return httpProxies;
        }
        // For other schemes or if no specific proxy is desired, return NO_PROXY
        return List.of(Proxy.NO_PROXY);
    }

    private boolean canProxy(URI uri) {
        if (!isHttpProtocol(uri)) {
            return false;
        }
        if (proxyFilter == null) {
            return true;
        }
        SocketAddressImpl sa = new SocketAddressImpl(uri.getPort(), uri.getHost());
        return proxyFilter.test(sa);
    }

    private static boolean isHttpProtocol(URI uri) {
        String scheme = uri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        log.debug(String.format("Failed to connect to proxy %s", uri.toString()), ioe);
    }
}
