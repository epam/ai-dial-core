package com.epam.aidial.core.server.http;

import com.epam.aidial.core.server.TestWebServer;
import io.vertx.core.net.ProxyOptions;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URL;
import java.util.List;

public class HttpProxySelectorTest {

    @Test
    @SneakyThrows
    public void testSelect() {
        ProxyOptions proxyOptions = new ProxyOptions();
        proxyOptions.setHost("localhost");
        proxyOptions.setPort(9083);
        // ProxySelector.setDefault is JVM-wide: left set, it would route every later test's default HTTP
        // client through this test's proxy, which is gone once the test ends
        ProxySelector originalDefault = ProxySelector.getDefault();
        try (var ignored = new TestWebServer(9083)) {
            URL httpUrl = URI.create("http://some-host").toURL();
            // non-proxy hosts is missed
            ProxySelector selector = new HttpProxySelector(proxyOptions, null);
            ProxySelector.setDefault(selector);
            Assertions.assertDoesNotThrow(() -> checkConnection(httpUrl));
            // non-proxy host is matched
            List<String> nonProxyList = List.of("some-host");
            selector = new HttpProxySelector(proxyOptions, nonProxyList);
            ProxySelector.setDefault(selector);
            Assertions.assertThrows(Throwable.class, () -> checkConnection(httpUrl));
            // non-proxy host is not matched
            nonProxyList = List.of("trusted-host");
            selector = new HttpProxySelector(proxyOptions, nonProxyList);
            ProxySelector.setDefault(selector);
            Assertions.assertDoesNotThrow(() -> checkConnection(httpUrl));
        } finally {
            ProxySelector.setDefault(originalDefault);
        }
    }

    @SneakyThrows
    private void checkConnection(URL httpUrl) {
        HttpURLConnection httpConn = (HttpURLConnection) httpUrl.openConnection();
        httpConn.connect();
        httpConn.disconnect();
    }
}
