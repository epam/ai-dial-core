package com.epam.aidial.core.security;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkException;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class IdentityProviderTest {

    @Mock
    private JwkProvider jwkProvider;

    @Mock
    private Vertx vertx;

    private JsonObject settings;

    private static KeyPair keyPair;

    @BeforeAll
    public static void beforeAll() throws NoSuchAlgorithmException {
        keyPair = generateRsa256Pair();
    }

    @BeforeEach
    public void beforeEach() {
        settings = new JsonObject();
        settings.put("jwksUrl", "http://host/jwks");
        settings.put("rolePath", "roles");
        settings.put("issuerPattern", "issuer");
    }

    @Test
    public void testExtractClaims_00() {
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, url -> jwkProvider);
        Future<ExtractedClaims> result = identityProvider.extractClaims(null, true);
        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.failed());
            assertNotNull(res.cause());
        });
    }

    @Test
    public void testExtractClaims_03() {
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, url -> jwkProvider);
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        String token = JWT.create().withClaim("roles", List.of("manager")).sign(algorithm);
        Future<ExtractedClaims> result = identityProvider.extractClaims(JWT.decode(token), false);
        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNotNull(claims);
            assertEquals(List.of("manager"), claims.userRoles());
        });
    }

    @Test
    public void testExtractClaims_04() {
        settings.put("rolePath", "p0.p1.p2.p3");
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, url -> jwkProvider);
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        Map<String, Object> claim = Map.of("some", "val", "k1", 12, "p1", Map.of("p2", Map.of("p3", List.of("r1", "r2"))));
        String token = JWT.create().withClaim("p0", claim).sign(algorithm);
        Future<ExtractedClaims> result = identityProvider.extractClaims(JWT.decode(token), false);
        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNotNull(claims);
            assertEquals(List.of("r1", "r2"), claims.userRoles());
        });
    }

    @Test
    public void testExtractClaims_05() {
        settings.put("rolePath", "p0.p1");
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, url -> jwkProvider);
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        Map<String, Object> claim = Map.of("some", "val", "k1", 12, "p1", List.of("r1", "r2"));
        String token = JWT.create().withClaim("p0", claim).sign(algorithm);
        Future<ExtractedClaims> result = identityProvider.extractClaims(JWT.decode(token), false);
        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNotNull(claims);
            assertEquals(List.of("r1", "r2"), claims.userRoles());
        });
    }

    @Test
    public void testExtractClaims_06() {
        settings.put("rolePath", "p0.p1.p2.p3");
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, url -> jwkProvider);
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        Map<String, Object> claim = Map.of("some", "val", "k1", 12, "p1", Map.of("p2", List.of("p3", List.of("r1", "r2"))));
        String token = JWT.create().withClaim("p0", claim).sign(algorithm);
        Future<ExtractedClaims> result = identityProvider.extractClaims(JWT.decode(token), false);
        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNotNull(claims);
            assertEquals(Collections.emptyList(), claims.userRoles());
        });
    }

    @Test
    public void testExtractClaims_07() throws JwkException {
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, url -> jwkProvider);
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());
        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(vertx.executeBlocking(any(Handler.class))).thenAnswer(invocation -> {
            Handler<Promise<?>> h = invocation.getArgument(0);
            Promise<?> p = Promise.promise();
            h.handle(p);
            return p.future();
        });
        String token = JWT.create().withHeader(Map.of("kid", "kid1")).withClaim("roles", List.of("manager")).sign(algorithm);
        Future<ExtractedClaims> result = identityProvider.extractClaims(JWT.decode(token), true);
        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNotNull(claims);
            assertEquals(List.of("manager"), claims.userRoles());
        });
    }

    @Test
    public void testExtractClaims_08() throws JwkException {
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, url -> jwkProvider);
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());
        when(jwkProvider.get(eq("kid1"))).thenThrow(new JwkException("no key found by kid1"));
        when(vertx.executeBlocking(any(Handler.class))).thenAnswer(invocation -> {
            Handler<Promise<?>> h = invocation.getArgument(0);
            Promise<?> p = Promise.promise();
            h.handle(p);
            return p.future();
        });
        String token = JWT.create().withHeader(Map.of("kid", "kid1")).withClaim("roles", List.of("manager")).sign(algorithm);
        Future<ExtractedClaims> result = identityProvider.extractClaims(JWT.decode(token), true);
        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.failed());
            ExtractedClaims claims = res.result();
            assertNull(claims);
        });
    }

    @Test
    public void testExtractClaims_09() {
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, url -> jwkProvider);
        Future<ExtractedClaims> result = identityProvider.extractClaims(null, false);
        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            assertNull(res.result());
        });
    }

    @Test
    public void testExtractClaims_10() throws JwkException, NoSuchAlgorithmException {
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, url -> jwkProvider);
        KeyPair wrongKeyPair = generateRsa256Pair();
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) wrongKeyPair.getPublic(), (RSAPrivateKey) wrongKeyPair.getPrivate());
        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(vertx.executeBlocking(any(Handler.class))).thenAnswer(invocation -> {
            Handler<Promise<?>> h = invocation.getArgument(0);
            Promise<?> p = Promise.promise();
            h.handle(p);
            return p.future();
        });
        String token = JWT.create().withHeader(Map.of("kid", "kid1")).withClaim("roles", List.of("manager")).sign(algorithm);
        Future<ExtractedClaims> result = identityProvider.extractClaims(JWT.decode(token), true);
        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.failed());
            assertNotNull(res.cause());
        });
    }

    @Test
    public void testExtractClaims_11() {
        settings.put("rolePath", "p0.p1.p2.p3");
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, url -> jwkProvider);
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        String token = JWT.create().withClaim("some", "val").sign(algorithm);
        Future<ExtractedClaims> result = identityProvider.extractClaims(JWT.decode(token), false);
        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNotNull(claims);
            assertEquals(Collections.emptyList(), claims.userRoles());
        });
    }

    @Test
    public void testMatch_Failure() {
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, url -> jwkProvider);
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());
        String token = JWT.create().withClaim("iss", "bad-iss").sign(algorithm);
        DecodedJWT jwt = JWT.decode(token);
        assertFalse(identityProvider.match(jwt));
    }

    @Test
    public void testMatch_Success() {
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, url -> jwkProvider);
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());
        String token = JWT.create().withClaim("iss", "issuer").sign(algorithm);
        DecodedJWT jwt = JWT.decode(token);
        assertTrue(identityProvider.match(jwt));
    }

    private static KeyPair generateRsa256Pair() throws NoSuchAlgorithmException {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(512);
        return keyGen.genKeyPair();
    }
}
