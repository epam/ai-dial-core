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
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.RequestOptions;
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
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class IdentityProviderTest {

    @Mock
    private JwkProvider jwkProvider;

    @Mock
    private Vertx vertx;

    @Mock
    private HttpClient client;

    @Mock
    private GetUserRoleFunctionFactory factory;

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
        settings.put("loggingKey", "email");
        settings.put("loggingSalt", "salt");
    }

    @Test
    public void testExtractClaims_00() {
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, client, url -> jwkProvider, factory);

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(null);

        assertNotNull(result);

        result.onComplete(res -> {
            assertTrue(res.failed());
            assertNotNull(res.cause());
        });
    }

    @Test
    public void testExtractClaims_03() throws JwkException {
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, client, url -> jwkProvider, factory);
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        String token = JWT.create().withHeader(Map.of("kid", "kid1")).withClaim("roles", List.of("manager")).sign(algorithm);
        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(vertx.executeBlocking(any(Callable.class), eq(false))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNotNull(claims);
            assertEquals(List.of("manager"), claims.userRoles());
        });
    }

    @Test
    public void testExtractClaims_04() throws JwkException {
        settings.put("rolePath", "p0.p1.p2.p3");
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, client, url -> jwkProvider, factory);
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(vertx.executeBlocking(any(Callable.class), eq(false))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });
        Map<String, Object> claim = Map.of("some", "val", "k1", 12, "p1", Map.of("p2", Map.of("p3", List.of("r1", "r2"))));
        String token = JWT.create().withHeader(Map.of("kid", "kid1")).withClaim("p0", claim).sign(algorithm);

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNotNull(claims);
            assertEquals(List.of("r1", "r2"), claims.userRoles());
        });
    }

    @Test
    public void testExtractClaims_05() throws JwkException {
        settings.put("rolePath", "p0.p1");
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, client, url -> jwkProvider, factory);
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(vertx.executeBlocking(any(Callable.class), eq(false))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });
        Map<String, Object> claim = Map.of("some", "val", "k1", 12, "p1", List.of("r1", "r2"));
        String token = JWT.create().withHeader(Map.of("kid", "kid1")).withClaim("p0", claim).sign(algorithm);

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNotNull(claims);
            assertEquals(List.of("r1", "r2"), claims.userRoles());
        });
    }

    @Test
    public void testExtractClaims_06() throws JwkException {
        settings.put("rolePath", "p0.p1.p2.p3");
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, client, url -> jwkProvider, factory);
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());
        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(vertx.executeBlocking(any(Callable.class), eq(false))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });
        Map<String, Object> claim = Map.of("some", "val", "k1", 12, "p1", Map.of("p2", List.of("p3", List.of("r1", "r2"))));
        String token = JWT.create().withHeader(Map.of("kid", "kid1")).withClaim("p0", claim).sign(algorithm);

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

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
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, client, url -> jwkProvider, factory);
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());
        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(vertx.executeBlocking(any(Callable.class), eq(false))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });
        String token = JWT.create().withHeader(Map.of("kid", "kid1")).withClaim("roles", List.of("manager")).sign(algorithm);

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

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
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, client, url -> jwkProvider, factory);
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());
        when(jwkProvider.get(eq("kid1"))).thenThrow(new JwkException("no key found by kid1"));
        when(vertx.executeBlocking(any(Callable.class), eq(false))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });
        String token = JWT.create().withHeader(Map.of("kid", "kid1")).withClaim("roles", List.of("manager")).sign(algorithm);

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.failed());
            ExtractedClaims claims = res.result();
            assertNull(claims);
        });
    }

    @Test
    public void testExtractClaims_10() throws JwkException, NoSuchAlgorithmException {
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, client, url -> jwkProvider, factory);
        KeyPair wrongKeyPair = generateRsa256Pair();
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) wrongKeyPair.getPublic(), (RSAPrivateKey) wrongKeyPair.getPrivate());
        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(vertx.executeBlocking(any(Callable.class), eq(false))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });
        String token = JWT.create().withHeader(Map.of("kid", "kid1")).withClaim("roles", List.of("manager")).sign(algorithm);

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.failed());
            assertNotNull(res.cause());
        });
    }

    @Test
    public void testExtractClaims_11() throws JwkException {
        settings.put("rolePath", "p0.p1.p2.p3");
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, client, url -> jwkProvider, factory);
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());
        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(vertx.executeBlocking(any(Callable.class), eq(false))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });
        String token = JWT.create().withHeader(Map.of("kid", "kid1")).withClaim("some", "val").sign(algorithm);

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNotNull(claims);
            assertEquals(Collections.emptyList(), claims.userRoles());
        });
    }

    @Test
    public void testExtractClaims_12() {
        settings.put("disableJwtVerification", Boolean.TRUE);
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, client, url -> jwkProvider, factory);
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        String token = JWT.create().withHeader(Map.of("kid", "kid1"))
                .withClaim("roles", List.of("role"))
                .withClaim("email", "test@email.com")
                .withClaim("sub", "sub").sign(algorithm);

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

        verifyNoInteractions(jwkProvider);

        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNotNull(claims);
            assertEquals(List.of("role"), claims.userRoles());
            assertEquals("sub", claims.sub());
            assertNotNull(claims.userHash());
        });
    }

    @Test
    public void testExtractClaims_13() {
        settings.put("disableJwtVerification", Boolean.TRUE);
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, client, url -> jwkProvider, factory);
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        String token = JWT.create().withHeader(Map.of("kid", "kid1"))
                .withClaim("roles", List.of("role"))
                .withClaim("email", "test@email.com")
                .withClaim("id", 15)
                .withClaim("title", "title")
                .withClaim("access", List.of("read", "write"))
                .withClaim("expire", new Date(1713355825858L))
                .withClaim("numberList", List.of("15", "17", "34"))
                .withClaim("map", Map.of("a", List.of("b")))
                .withClaim("sub", "sub").sign(algorithm);

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

        verifyNoInteractions(jwkProvider);

        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNotNull(claims);
            assertEquals(List.of("role"), claims.userRoles());
            assertEquals("sub", claims.sub());
            assertNotNull(claims.userHash());
            Map<String, List<String>> userClaims = claims.userClaims();
            // assert user claim
            assertEquals(9, userClaims.size());
            assertEquals(List.of("sub"), userClaims.get("sub"));
            assertEquals(List.of("read", "write"), userClaims.get("access"));
            assertEquals(List.of("role"), userClaims.get("roles"));
            assertEquals(List.of(), userClaims.get("expire"));
            assertEquals(List.of("15", "17", "34"), userClaims.get("numberList"));
            assertEquals(List.of(), userClaims.get("id"));
            assertEquals(List.of("title"), userClaims.get("title"));
            assertEquals(List.of(), userClaims.get("map"));
            assertEquals(List.of("test@email.com"), userClaims.get("email"));
        });
    }

    @Test
    public void testExtractClaims_FromUserInfo_01() {
        settings.remove("jwksUrl");
        settings.put("userInfoEndpoint", "http://host/userinfo");
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, client, url -> jwkProvider, factory);

        String token = "opaqueToken";
        HttpClientRequest request = mock(HttpClientRequest.class);
        when(client.request(any(RequestOptions.class))).thenReturn(Future.succeededFuture(request));
        HttpClientResponse response = mock(HttpClientResponse.class);
        when(request.send()).thenReturn(Future.succeededFuture(response));
        Buffer buffer = Buffer.buffer("""
                {
                  "sub": "sub",
                  "email": "email",
                  "roles": ["role1"]
                }
                """);

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromUserInfo(token);

        verifyNoInteractions(jwkProvider);

        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.failed());
        });
    }

    @Test
    public void testExtractClaims_FromUserInfo_02() {
        settings.remove("jwksUrl");
        settings.put("userInfoEndpoint", "http://host/userinfo");
        settings.put("rolePath", "app.roles");
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, client, url -> jwkProvider, factory);

        String token = "opaqueToken";
        HttpClientRequest request = mock(HttpClientRequest.class);
        when(client.request(any(RequestOptions.class))).thenReturn(Future.succeededFuture(request));
        HttpClientResponse response = mock(HttpClientResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(request.send()).thenReturn(Future.succeededFuture(response));
        Buffer buffer = Buffer.buffer("""
                {
                  "sub": "sub",
                  "email": "email",
                  "app" : {
                    "roles": ["role1"]
                  }
                }
                """);
        when(response.body()).thenReturn(Future.succeededFuture(buffer));

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromUserInfo(token);

        verifyNoInteractions(jwkProvider);

        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNotNull(claims);
            assertEquals(List.of("role1"), claims.userRoles());
            assertEquals("sub", claims.sub());
            assertNotNull(claims.userHash());
        });
    }

    @Test
    public void testExtractClaims_FromUserInfo_03() {
        settings.remove("jwksUrl");
        settings.put("userInfoEndpoint", "http://host/userinfo");
        settings.put("rolePath", "fn:getGoogleWorkspaceGroups");
        GetUserRoleFn fn = mock(GetUserRoleFn.class);
        when(factory.getUserRoleFn(eq("fn:getGoogleWorkspaceGroups"))).thenReturn(fn);

        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, client, url -> jwkProvider, factory);

        String token = "opaqueToken";
        HttpClientRequest request = mock(HttpClientRequest.class);
        when(client.request(any(RequestOptions.class))).thenReturn(Future.succeededFuture(request));
        HttpClientResponse response = mock(HttpClientResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(request.send()).thenReturn(Future.succeededFuture(response));
        Buffer buffer = Buffer.buffer("""
                {
                  "sub": "sub",
                  "email": "email"
                }
                """);
        when(response.body()).thenReturn(Future.succeededFuture(buffer));
        when(fn.apply(eq(token), anyMap())).thenReturn(Future.succeededFuture(List.of("role1")));

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromUserInfo(token);

        verifyNoInteractions(jwkProvider);

        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNotNull(claims);
            assertEquals(List.of("role1"), claims.userRoles());
            assertEquals("sub", claims.sub());
            assertNotNull(claims.userHash());
        });
    }

    @Test
    public void testMatch_Failure() {
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, client, url -> jwkProvider, factory);
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());
        String token = JWT.create().withClaim("iss", "bad-iss").sign(algorithm);
        DecodedJWT jwt = JWT.decode(token);

        assertFalse(identityProvider.match(jwt));
    }

    @Test
    public void testMatch_Success() {
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, client, url -> jwkProvider, factory);
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
