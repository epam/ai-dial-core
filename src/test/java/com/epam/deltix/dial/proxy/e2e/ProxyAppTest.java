package com.epam.deltix.dial.proxy.e2e;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.epam.deltix.dial.proxy.ProxyApp;
import io.vertx.core.http.HttpHeaders;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.epam.deltix.dial.proxy.Proxy.HEADER_API_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ProxyAppTest {

    public static ProxyApp app;

    public static RSAPrivateKey privateKey;

    @BeforeAll
    public static void init() throws Exception {
        byte[] privateServerKey;
        try(InputStream pk = ProxyAppTest.class.getResourceAsStream("/private.key")) {
            if (pk == null) {
                throw new IllegalArgumentException("private.key not found");
            }
            privateServerKey = pk.readAllBytes();
        }
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PKCS8EncodedKeySpec spec =
                new PKCS8EncodedKeySpec(privateServerKey);
        privateKey = (RSAPrivateKey) keyFactory.generatePrivate(spec);
//        printPublicKeyAsBase64();
        app = new ProxyApp();
        app.start();
    }

    private void printPublicKeyAsBase64() throws IOException {
        try (InputStream in = ProxyAppTest.class.getResourceAsStream("/public.key")) {
            if (in == null) {
                throw new IllegalArgumentException("public.key not found");
            }
            byte[] byteKey = in.readAllBytes();
            System.out.println(new String(Base64.getEncoder().encode(byteKey)));
        }
    }

    @Test
    public void testGetDeployment_Success() throws Exception {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpRequest request = createHttpRequest(Collections.singletonList("user"), "/openai/deployments/chat-gpt-35-turbo", "proxyKey1");
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
    }

    @Test
    public void testGetDeployment_Forbidden() throws Exception {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpRequest request = createHttpRequest(Collections.singletonList("user-has-no-perm"), "/openai/deployments/chat-gpt-35-turbo", "proxyKey1");
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, response.statusCode());
    }

    private HttpRequest createHttpRequest(List<String> userRoles, String path, String apiKey) throws Exception {
        String jwt = createJwt(userRoles);
        return HttpRequest.newBuilder(new URI("http://localhost:8080" + path))
                .header(HEADER_API_KEY, apiKey)
                .header(HttpHeaders.AUTHORIZATION.toString(), "Bearer " + jwt)
                .GET().build();
    }

    private String createJwt(List<String> roles) {
        Map<String, Object> resourceAccess = new HashMap<>();
        Map<String, List<String>> app = new HashMap<>();
        app.put("roles", roles);
        resourceAccess.put("openai-proxy", app);
        return JWT.create().withClaim("resource_access", resourceAccess).withClaim("name", "test").sign(Algorithm.RSA256(null, privateKey));
    }
}
