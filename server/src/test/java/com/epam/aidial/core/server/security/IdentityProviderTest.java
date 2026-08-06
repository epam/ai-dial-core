package com.epam.aidial.core.server.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkException;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

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
    private AsyncTaskExecutor taskExecutor;

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
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(null);

        assertNotNull(result);

        result.onComplete(res -> {
            assertTrue(res.failed());
            assertNotNull(res.cause());
        });
    }

    @Test
    public void testLogClaimsDefaultIncludesEmail() {
        try (LogContext logContext = LogContext.create()) {
            settings.put("disableJwtVerification", Boolean.TRUE);
            // claimPathsToLog NOT set - should default to [sub, oid, email]
            IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
            Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

            String token = JWT.create().withHeader(Map.of("kid", "kid1"))
                    .withClaim("roles", List.of("role"))
                    .withClaim("sub", "sub-value")
                    .withClaim("oid", "oid-value")
                    .withClaim("email", "user@test.com").sign(algorithm);

            Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

            verifyNoInteractions(jwkProvider);

            assertNotNull(result);
            result.onComplete(res -> {
                assertTrue(res.succeeded());
                logContext.assertMessages("[DEBUG] User login: sub=sub-value, oid=oid-value, email=user@test.com");
            });
        }
    }

    @Test
    public void testExtractClaims_03() throws JwkException {
        try (LogContext logContext = LogContext.create()) {
            settings.put("claimPathsToLog", List.of("roles"));
            IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
            Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

            String token = JWT.create().withHeader(Map.of("kid", "kid1")).withClaim("roles", List.of("manager")).sign(algorithm);
            Jwk jwk = mock(Jwk.class);
            when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
            when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
            when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
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
                logContext.assertMessages("[DEBUG] User login: roles=[manager]");
            });
        }
    }

    @Test
    public void testExtractClaims_04() throws JwkException {
        settings.put("rolePath", "p0.p1.p2.p3");
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
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
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
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
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());
        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
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
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());
        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
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
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());
        when(jwkProvider.get(eq("kid1"))).thenThrow(new JwkException("no key found by kid1"));
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
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
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        KeyPair wrongKeyPair = generateRsa256Pair();
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) wrongKeyPair.getPublic(), (RSAPrivateKey) wrongKeyPair.getPrivate());
        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
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
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());
        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
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
        try (LogContext logContext = LogContext.create()) {
            settings.put("disableJwtVerification", Boolean.TRUE);
            settings.put("claimPathsToLog", List.of("email"));
            IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
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
                assertEquals("sub", claims.userId());
                assertNotNull(claims.userHash());
                logContext.assertMessages("[DEBUG] User login: email=test@email.com");
            });
        }
    }

    @Test
    public void testExtractClaims_13() {
        settings.put("disableJwtVerification", Boolean.TRUE);
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
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
            assertEquals("sub", claims.userId());
            assertNotNull(claims.userHash());
            ObjectNode userClaims = claims.userClaims();
            // assert user claim
            assertEquals(9, userClaims.size());
        });
    }

    @Test
    public void testExtractClaims_14() {
        settings.put("disableJwtVerification", Boolean.TRUE);
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        String token = JWT.create().withHeader(Map.of("kid", "kid1"))
                // test with a single role as a string field
                .withClaim("roles", "role")
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
            assertEquals("sub", claims.userId());
            assertNotNull(claims.userHash());
            JsonNode userClaims = claims.userClaims();
            // assert user claim
            assertEquals(9, userClaims.size());
        });
    }

    @Test
    public void testExtractClaims_15() throws JwkException {
        settings.put("rolesDelimiter", " ");
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        String token = JWT.create().withHeader(Map.of("kid", "kid1")).withClaim("roles", "r1 r2 r3").sign(algorithm);
        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNotNull(claims);
            assertEquals(List.of("r1", "r2", "r3"), claims.userRoles());
        });
    }

    @Test
    public void testExtractClaims_16() throws JwkException {
        settings.put("rolesDelimiter", ":");
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        String token = JWT.create().withHeader(Map.of("kid", "kid1")).withClaim("roles", "r1 r2 r3").sign(algorithm);
        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNotNull(claims);
            assertEquals(List.of("r1 r2 r3"), claims.userRoles());
        });
    }

    @Test
    public void testExtractClaims_17() throws JwkException {
        settings.put("rolesDelimiter", " ");
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        String token = JWT.create().withHeader(Map.of("kid", "kid1")).withClaim("roles", List.of("r1", "r2 r3")).sign(algorithm);
        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNotNull(claims);
            assertEquals(List.of("r1", "r2 r3"), claims.userRoles());
        });
    }

    @Test
    public void testExtractClaims_18() throws JwkException {
        settings.put("rolesDelimiter", " ");
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        String token = JWT.create().withHeader(Map.of("kid", "kid1")).withClaim("roles", (String) null).sign(algorithm);
        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNotNull(claims);
            assertEquals(Collections.EMPTY_LIST, claims.userRoles());
        });
    }

    @Test
    public void testExtractClaims_19() throws JwkException {
        settings.put("rolesDelimiter", " ");
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        String token = JWT.create().withHeader(Map.of("kid", "kid1")).withClaim("roles", "").sign(algorithm);
        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

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
    public void testExtractClaims_20() throws JwkException {
        settings.put("rolesDelimiter", " ");
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        String token = JWT.create().withHeader(Map.of("kid", "kid1")).withClaim("roles", "r1 r2  r3   r4").sign(algorithm);
        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNotNull(claims);
            assertEquals(List.of("r1", "r2", "r3", "r4"), claims.userRoles());
        });
    }

    @Test
    public void testExtractClaims_21() throws JwkException {
        settings.put("rolePath", List.of("roles", "roles2"));
        settings.put("rolesDelimiter", " ");
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        String token = JWT.create().withHeader(Map.of("kid", "kid1"))
                .withClaim("roles", "r1 r2 r3")
                .withClaim("roles2", "r4 r5 r6")
                .sign(algorithm);
        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNotNull(claims);
            assertEquals(List.of("r1", "r2", "r3", "r4", "r5", "r6"), claims.userRoles());
        });
    }

    @Test
    public void testExtractClaims_22() throws JwkException {
        settings.put("rolePath", List.of("roles", "roles2"));
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        String token = JWT.create().withHeader(Map.of("kid", "kid1"))
                .withClaim("roles", "r1")
                .withClaim("roles2", "r2")
                .sign(algorithm);
        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

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
    public void testExtractClaims_23() throws JwkException {
        settings.put("rolePath", List.of("roles", "roles2"));
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        String token = JWT.create().withHeader(Map.of("kid", "kid1"))
                .withClaim("roles", List.of("r1", "r2", "r3"))
                .sign(algorithm);
        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNotNull(claims);
            assertEquals(List.of("r1", "r2", "r3"), claims.userRoles());
        });
    }

    @Test
    public void testExtractClaims_24() throws JwkException {
        settings.put("rolePath", List.of("roles", "roles2"));
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        String token = JWT.create().withHeader(Map.of("kid", "kid1"))
                .withClaim("roles", List.of("r1", "r2", "r3"))
                .withClaim("roles2", "r4")
                .sign(algorithm);
        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNotNull(claims);
            assertEquals(List.of("r1", "r2", "r3", "r4"), claims.userRoles());
        });
    }

    @Test
    public void testExtractClaims_25() throws JwkException {
        settings.put("rolePath", List.of("p0.p1.p2.p3", "p0.p1.p2.p4"));
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });
        Map<String, Object> claim = Map.of("some", "val", "k1", 12, "p1",
                Map.of("p2", Map.of("p3", List.of("r1", "r2"), "p4", List.of("r3", "r4"))));

        String token = JWT.create().withHeader(Map.of("kid", "kid1")).withClaim("p0", claim).sign(algorithm);

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNotNull(claims);
            assertEquals(List.of("r1", "r2", "r3", "r4"), claims.userRoles());
        });
    }

    @Test
    public void testExtractClaims_26() throws JwkException {
        settings.put("projectPath", "p0.p1.p2.p3");
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        Map<String, Object> claim = Map.of("some", "val", "k1", 12, "p1",
                Map.of("p2", Map.of("p3", "project1")));

        String token = JWT.create().withHeader(Map.of("kid", "kid1")).withClaim("p0", claim).sign(algorithm);

        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNotNull(claims);
            assertEquals("project1", claims.project());
        });
    }

    @Test
    public void testExtractClaims_27() throws JwkException {
        settings.put("projectPath", "p0");
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        Map<String, Object> claim = Map.of("some", "val", "k1", 12, "p1",
                Map.of("p2", Map.of("p3", "project1")));

        String token = JWT.create().withHeader(Map.of("kid", "kid1")).withClaim("p0", claim).sign(algorithm);

        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNotNull(claims);
            assertNull(claims.project());
        });
    }

    @Test
    public void testExtractClaims_28() throws JwkException {
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        Map<String, Object> claim = Map.of("some", "val", "k1", 12, "p1",
                Map.of("p2", Map.of("p3", "project1")));

        String token = JWT.create().withHeader(Map.of("kid", "kid1")).withClaim("p0", claim).sign(algorithm);

        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNotNull(claims);
            assertNull(claims.project());
        });
    }

    @Test
    public void testExtractClaims_29() throws JwkException {
        settings.put("projectPath", "azp");
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        String token = JWT.create().withHeader(Map.of("kid", "kid1")).withClaim("azp", "project1").sign(algorithm);

        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNotNull(claims);
            assertEquals("project1", claims.project());
        });
    }

    @Test
    public void testExtractClaims_30() throws JwkException {
        settings.put("audience", "dial");
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        String token = JWT.create().withHeader(Map.of("kid", "kid1")).withClaim("aud", "dial").withClaim("roles", List.of("manager")).sign(algorithm);
        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
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
    public void testExtractClaims_31() throws JwkException {
        settings.put("audience", "dial");
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        String token = JWT.create().withHeader(Map.of("kid", "kid1")).withClaim("aud", "wrong_aud").withClaim("roles", List.of("manager")).sign(algorithm);
        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

        assertNotNull(result);
        result.onComplete(res -> {
            assertFalse(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNull(claims);
        });
    }

    @Test
    public void testExtractClaims_32() throws JwkException {
        settings.put("userIdPath", "oid");
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        String token = JWT.create().withHeader(Map.of("kid", "kid1"))
                .withClaim("roles", List.of("manager")).withClaim("oid", "123").sign(algorithm);
        Jwk jwk = mock(Jwk.class);
        when(jwk.getPublicKey()).thenReturn(keyPair.getPublic());
        when(jwkProvider.get(eq("kid1"))).thenReturn(jwk);
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNotNull(claims);
            assertEquals("123", claims.userId());
        });
    }

    @Test
    public void testExtractClaims_FromUserInfo_01() {
        settings.remove("jwksUrl");
        settings.put("userInfoEndpoint", "http://host/userinfo");
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");

        String token = "opaqueToken";
        HttpClientRequest request = mock(HttpClientRequest.class);
        when(client.request(any(RequestOptions.class))).thenReturn(Future.succeededFuture(request));
        HttpClientResponse response = mock(HttpClientResponse.class);
        when(request.send()).thenReturn(Future.succeededFuture(response));

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromUserInfo(token);

        verifyNoInteractions(jwkProvider);

        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.failed());
        });
    }

    @Test
    public void testExtractClaims_FromUserInfo_02() {
        try (LogContext logContext = LogContext.create()) {
            settings.remove("jwksUrl");
            settings.put("userInfoEndpoint", "http://host/userinfo");
            settings.put("rolePath", "app.roles");
            settings.put("claimPathsToLog", List.of("sub", "oid", "app.roles"));
            IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");

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
                assertEquals("sub", claims.userId());
                assertNotNull(claims.userHash());
                logContext.assertMessages("[DEBUG] User login: sub=sub, oid=null, app.roles=[role1]");
            });
        }
    }

    @Test
    public void testExtractClaims_FromUserInfo_03() {
        try (LogContext logContext = LogContext.create()) {
            settings.remove("jwksUrl");
            settings.put("userInfoEndpoint", "http://host/userinfo");
            settings.put("rolePath", "fn:getGoogleWorkspaceGroups");
            settings.put("claimPathsToLog", List.of("sub", "email"));
            GetUserRoleFn fn = mock(GetUserRoleFn.class);
            when(factory.getUserRoleFn(eq("fn:getGoogleWorkspaceGroups"))).thenReturn(fn);

            IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");

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
                assertEquals("sub", claims.userId());
                assertNotNull(claims.userHash());
                logContext.assertMessages("[DEBUG] User login: sub=sub, email=email");
            });
        }
    }

    @Test
    public void testLogClaimsAtInfoLevel_Jwt() {
        try (LogContext logContext = LogContext.create()) {
            settings.put("disableJwtVerification", Boolean.TRUE);
            settings.put("claimPathsToLog", List.of("email", "sub"));
            IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "INFO");
            Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

            String token = JWT.create().withHeader(Map.of("kid", "kid1"))
                    .withClaim("roles", List.of("role"))
                    .withClaim("email", "test@email.com")
                    .withClaim("sub", "sub-value").sign(algorithm);

            Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

            verifyNoInteractions(jwkProvider);

            assertNotNull(result);
            result.onComplete(res -> {
                assertTrue(res.succeeded());
                logContext.assertMessages("[INFO] User login: email=test@email.com, sub=sub-value");
            });
        }
    }

    @Test
    public void testLogClaimsAtDebugLevelByDefault() {
        try (LogContext logContext = LogContext.create()) {
            settings.put("disableJwtVerification", Boolean.TRUE);
            settings.put("claimPathsToLog", List.of("email"));
            // logClaimsAtInfoLevel NOT set - should default to false (DEBUG level)
            IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
            Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

            String token = JWT.create().withHeader(Map.of("kid", "kid1"))
                    .withClaim("roles", List.of("role"))
                    .withClaim("email", "test@email.com").sign(algorithm);

            Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

            verifyNoInteractions(jwkProvider);

            assertNotNull(result);
            result.onComplete(res -> {
                assertTrue(res.succeeded());
                logContext.assertMessages("[DEBUG] User login: email=test@email.com");
            });
        }
    }

    @Test
    public void testLogClaimsAtInfoLevel_UserInfo() {
        try (LogContext logContext = LogContext.create()) {
            settings.remove("jwksUrl");
            settings.put("userInfoEndpoint", "http://host/userinfo");
            settings.put("rolePath", "app.roles");
            settings.put("claimPathsToLog", List.of("sub", "email"));
            IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "INFO");

            String token = "opaqueToken";
            HttpClientRequest request = mock(HttpClientRequest.class);
            when(client.request(any(RequestOptions.class))).thenReturn(Future.succeededFuture(request));
            HttpClientResponse response = mock(HttpClientResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(request.send()).thenReturn(Future.succeededFuture(response));
            Buffer buffer = Buffer.buffer("""
                    {
                      "sub": "sub",
                      "email": "email@test.com",
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
                logContext.assertMessages("[INFO] User login: sub=sub, email=email@test.com");
            });
        }
    }

    @Test
    public void testMatch_Failure() {
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());
        String token = JWT.create().withClaim("iss", "bad-iss").sign(algorithm);
        DecodedJWT jwt = JWT.decode(token);

        assertFalse(identityProvider.match(jwt));
    }

    @Test
    public void testMatch_Success() {
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());
        String token = JWT.create().withClaim("iss", "issuer").sign(algorithm);
        DecodedJWT jwt = JWT.decode(token);

        assertTrue(identityProvider.match(jwt));
    }

    @Test
    public void testExtractUserEmail_DefaultPath() {
        settings.put("disableJwtVerification", Boolean.TRUE);
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        String token = JWT.create().withHeader(Map.of("kid", "kid1"))
                .withClaim("roles", List.of("role"))
                .withClaim("email", "user@test.com").sign(algorithm);

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            assertEquals("user@test.com", res.result().userEmail());
        });
    }

    @Test
    public void testExtractUserEmail_CustomPath() {
        settings.put("disableJwtVerification", Boolean.TRUE);
        settings.put("userEmailPath", "profile.mail");
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        String token = JWT.create().withHeader(Map.of("kid", "kid1"))
                .withClaim("roles", List.of("role"))
                .withClaim("email", "ignored@test.com")
                .withClaim("profile", Map.of("mail", "user@test.com")).sign(algorithm);

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            assertEquals("user@test.com", res.result().userEmail());
        });
    }

    @Test
    public void testExtractUserEmail_ClaimMissing() {
        settings.put("disableJwtVerification", Boolean.TRUE);
        IdentityProvider identityProvider = new IdentityProvider(settings, vertx, taskExecutor, client, url -> jwkProvider, factory, "DEBUG");
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());

        String token = JWT.create().withHeader(Map.of("kid", "kid1")).withClaim("roles", List.of("role")).sign(algorithm);

        Future<ExtractedClaims> result = identityProvider.extractClaimsFromJwt(JWT.decode(token));

        assertNotNull(result);
        result.onComplete(res -> {
            assertTrue(res.succeeded());
            assertNull(res.result().userEmail());
        });
    }

    private static KeyPair generateRsa256Pair() throws NoSuchAlgorithmException {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(512);
        return keyGen.genKeyPair();
    }

    @RequiredArgsConstructor
    private static class LogContext implements AutoCloseable {
        private final Logger logger;
        private final Level previousLevel;
        private final ListAppender<ILoggingEvent> appender;

        @Override
        public void close() {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }

        public void assertMessages(String... expected) {
            List<String> actual = appender.list.stream()
                    .map(event -> "[" + event.getLevel() + "] " + event.getFormattedMessage())
                    .toList();
            assertEquals(List.of(expected), actual);
        }

        public static LogContext create() {
            Logger logger = (Logger) LoggerFactory.getLogger(IdentityProvider.class);
            Level previousLevel = logger.getLevel();
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            logger.setLevel(Level.DEBUG);

            return new LogContext(logger, previousLevel, appender);
        }
    }
}
