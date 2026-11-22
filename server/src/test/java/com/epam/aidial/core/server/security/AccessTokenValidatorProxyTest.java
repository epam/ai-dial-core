package com.epam.aidial.core.server.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.epam.aidial.core.server.TestWebServer;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.tracing.BlockingCallTracer;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.ProxyOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Regression tests for the JWKS fetch honoring {@code aidial.client.proxyOptions}.
 * {@code jwks.invalid} is a reserved TLD (RFC 2606) that never resolves, so a direct fetch
 * fails at DNS resolution, while a fetch routed through the configured proxy reaches the
 * stub server below regardless of the (unresolvable) target host in the request line.
 */
@ExtendWith(VertxExtension.class)
public class AccessTokenValidatorProxyTest {

    private static final String JWKS_URL = "http://jwks.invalid/keys";

    @Test
    public void testJwksFetchGoesThroughConfiguredProxy(Vertx vertx, VertxTestContext testContext) throws Throwable {
        try (TestWebServer proxyServer = new TestWebServer(19187, request -> TestWebServer.createResponse(200, "{\"keys\":[]}"))) {
            HttpClientOptions clientOptions = new HttpClientOptions();
            clientOptions.setProxyOptions(new ProxyOptions().setHost("localhost").setPort(19187));

            AccessTokenValidator validator = newValidator(vertx, clientOptions);
            String token = signedToken();

            validator.extractClaims("bearer " + token).onComplete(res -> testContext.verify(() -> {
                assertTrue(res.failed());
                assertTrue(res.cause().getMessage().contains("No keys found"),
                        "Expected the request to reach the proxy stub and get an (empty) JWKS response, but got: " + res.cause());
                testContext.completeNow();
            }));

            await(testContext);
        }
    }

    @Test
    public void testJwksFetchWithoutProxyFailsDirectly(Vertx vertx, VertxTestContext testContext) throws Throwable {
        AccessTokenValidator validator = newValidator(vertx, new HttpClientOptions());
        String token = signedToken();

        validator.extractClaims("bearer " + token).onComplete(res -> testContext.verify(() -> {
            assertTrue(res.failed());
            assertTrue(res.cause().getMessage().contains("Cannot obtain jwks from url"),
                    "Expected a direct connection failure (no proxy configured), but got: " + res.cause());
            testContext.completeNow();
        }));

        await(testContext);
    }

    private static AccessTokenValidator newValidator(Vertx vertx, HttpClientOptions clientOptions) {
        JsonObject idpConfig = new JsonObject()
                .put("idp1", JsonObject.of("jwksUrl", JWKS_URL, "rolePath", "role1"));
        HttpClient client = mock(HttpClient.class);
        AsyncTaskExecutor taskExecutor = new AsyncTaskExecutor(vertx, new JsonObject(Map.of("useVirtualThreads", false)));
        return new AccessTokenValidator(idpConfig, vertx, taskExecutor, client, clientOptions, "DEBUG", BlockingCallTracer.NOOP);
    }

    private static String signedToken() throws NoSuchAlgorithmException {
        KeyPair keyPair = generateRsa256Pair();
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());
        return JWT.create().withKeyId("kid1").withClaim("iss", "issuer").sign(algorithm);
    }

    private static KeyPair generateRsa256Pair() throws NoSuchAlgorithmException {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(512);
        return keyGen.genKeyPair();
    }

    private static void await(VertxTestContext testContext) throws Throwable {
        assertNotNull(testContext);
        if (!testContext.awaitCompletion(10, TimeUnit.SECONDS)) {
            throw new AssertionError("Test timed out waiting for completion");
        }
        if (testContext.failed()) {
            throw testContext.causeOfFailure();
        }
    }
}
