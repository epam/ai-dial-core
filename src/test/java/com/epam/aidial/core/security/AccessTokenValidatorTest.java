package com.epam.aidial.core.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.json.JsonObject;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class AccessTokenValidatorTest {

    @Mock
    private Vertx vertx;

    @Mock
    private HttpClient client;

    private JsonObject idpConfig;

    @BeforeEach
    public void beforeEach() {
        idpConfig = new JsonObject();
        idpConfig.put("idp1", JsonObject.of("jwksUrl", "http://host1/keys", "rolePath", "role1", "issuerPattern", "issue1"));
        idpConfig.put("ipd2", JsonObject.of("jwksUrl", "http://host2/keys", "rolePath", "role2", "issuerPattern", "issue2"));
    }

    @Test
    public void testExtractClaims_01() {
        AccessTokenValidator validator = new AccessTokenValidator(idpConfig, vertx, client);
        Future<ExtractedClaims> future = validator.extractClaims(null);
        assertNotNull(future);
        future.onComplete(res -> {
            assertTrue(res.succeeded());
            assertNull(res.result());
        });
    }

    @Test
    public void testExtractClaims_02() {
        AccessTokenValidator validator = new AccessTokenValidator(idpConfig, vertx, client);
        Future<ExtractedClaims> future = validator.extractClaims("bad-auth-header");
        assertNotNull(future);
        future.onComplete(res -> {
            assertTrue(res.failed());
            assertNotNull(res.cause());
        });
    }

    @Test
    public void testExtractClaims_03() {
        AccessTokenValidator validator = new AccessTokenValidator(idpConfig, vertx, client);
        Future<ExtractedClaims> future = validator.extractClaims("bearer bad-token");
        assertNotNull(future);
        future.onComplete(res -> {
            assertTrue(res.failed());
            assertNotNull(res.cause());
        });
    }

    @Test
    public void testExtractClaims_04() throws NoSuchAlgorithmException {
        AccessTokenValidator validator = new AccessTokenValidator(idpConfig, vertx, client);
        IdentityProvider provider1 = mock(IdentityProvider.class);
        when(provider1.match(any(DecodedJWT.class))).thenReturn(false);
        IdentityProvider provider2 = mock(IdentityProvider.class);
        when(provider2.match(any(DecodedJWT.class))).thenReturn(false);
        List<IdentityProvider> providerList = List.of(provider1, provider2);
        validator.setProviders(providerList);
        KeyPair keyPair = generateRsa256Pair();
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());
        String token = JWT.create().withClaim("iss", "unknown-issuer").sign(algorithm);
        Future<ExtractedClaims> future = validator.extractClaims(getBearerHeaderValue(token));
        assertNotNull(future);
        future.onComplete(res -> {
            assertTrue(res.failed());
            assertNotNull(res.cause());
        });
    }

    @Test
    public void testExtractClaims_05() throws NoSuchAlgorithmException {
        AccessTokenValidator validator = new AccessTokenValidator(idpConfig, vertx, client);
        IdentityProvider provider1 = mock(IdentityProvider.class);
        when(provider1.match(any(DecodedJWT.class))).thenReturn(false);
        IdentityProvider provider2 = mock(IdentityProvider.class);
        when(provider2.match(any(DecodedJWT.class))).thenReturn(true);
        when(provider2.extractClaimsFromJwt(any(DecodedJWT.class))).thenReturn(Future.succeededFuture(new ExtractedClaims("sub", Collections.emptyList(), "hash", Map.of())));
        List<IdentityProvider> providerList = List.of(provider1, provider2);
        validator.setProviders(providerList);
        KeyPair keyPair = generateRsa256Pair();
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());
        String token = JWT.create().withClaim("iss", "issuer2").sign(algorithm);
        Future<ExtractedClaims> future = validator.extractClaims(getBearerHeaderValue(token));
        assertNotNull(future);
        future.onComplete(res -> {
            assertTrue(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNotNull(claims);
            assertEquals("sub", claims.sub());
            assertEquals(Collections.emptyList(), claims.userRoles());
            assertEquals("hash", claims.userHash());
        });
    }

    @Test
    public void testExtractClaims_06() throws NoSuchAlgorithmException {
        AccessTokenValidator validator = new AccessTokenValidator(idpConfig, vertx, client);
        IdentityProvider provider = mock(IdentityProvider.class);
        when(provider.hasUserinfoUrl()).thenReturn(false);
        when(provider.extractClaimsFromJwt(any(DecodedJWT.class))).thenReturn(Future.succeededFuture(new ExtractedClaims("sub", Collections.emptyList(), "hash", Map.of())));
        List<IdentityProvider> providerList = List.of(provider);
        validator.setProviders(providerList);
        KeyPair keyPair = generateRsa256Pair();
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());
        String token = JWT.create().withClaim("iss", "issuer").sign(algorithm);
        Future<ExtractedClaims> future = validator.extractClaims(getBearerHeaderValue(token));
        assertNotNull(future);
        future.onComplete(res -> {
            assertTrue(res.succeeded());
            ExtractedClaims claims = res.result();
            assertNotNull(claims);
            assertEquals("sub", claims.sub());
            assertEquals(Collections.emptyList(), claims.userRoles());
            assertEquals("hash", claims.userHash());
            verify(provider, never()).match(any(DecodedJWT.class));
        });
    }

    @Test
    public void testExtractClaims_07() {
        AccessTokenValidator validator = new AccessTokenValidator(idpConfig, vertx, client);
        IdentityProvider provider = mock(IdentityProvider.class);
        List<IdentityProvider> providerList = List.of(provider);
        validator.setProviders(providerList);
        String opaqueToken = "token";
        Future<ExtractedClaims> future = validator.extractClaims(getBearerHeaderValue(opaqueToken));
        assertNotNull(future);
        future.onComplete(res -> {
            assertTrue(res.failed());
        });
    }

    @Test
    public void testExtractClaims_08() {
        AccessTokenValidator validator = new AccessTokenValidator(idpConfig, vertx, client);
        IdentityProvider provider = mock(IdentityProvider.class);
        when(provider.hasUserinfoUrl()).thenReturn(true);
        ExtractedClaims extractedClaims = new ExtractedClaims("sub", List.of("role1"), "hash", Map.of());
        when(provider.extractClaimsFromUserInfo(anyString())).thenReturn(Future.succeededFuture(extractedClaims));
        List<IdentityProvider> providerList = List.of(provider);
        validator.setProviders(providerList);
        String opaqueToken = "token";
        Future<ExtractedClaims> future = validator.extractClaims(getBearerHeaderValue(opaqueToken));
        assertNotNull(future);
        future.onComplete(res -> {
            assertTrue(res.succeeded());
            assertEquals(extractedClaims, res.result());
        });
    }

    @Test
    public void testExtractTokenFromHeader() {
        assertNull(AccessTokenValidator.extractTokenFromHeader(null));
        assertNull(AccessTokenValidator.extractTokenFromHeader("wrong-token"));
        assertEquals("token", AccessTokenValidator.extractTokenFromHeader("bearer token"));
        assertEquals("token", AccessTokenValidator.extractTokenFromHeader("bearer token more"));
    }

    private static String getBearerHeaderValue(String token) {
        return String.format("bearer %s", token);
    }

    private static KeyPair generateRsa256Pair() throws NoSuchAlgorithmException {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(512);
        return keyGen.genKeyPair();
    }

}
