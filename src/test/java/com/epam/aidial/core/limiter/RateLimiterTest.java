package com.epam.aidial.core.limiter;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.ApiKeyData;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.security.ExtractedClaims;
import com.epam.aidial.core.security.IdentityProvider;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
public class RateLimiterTest {

    @Mock
    private HttpServerRequest request;

    private RateLimiter rateLimiter;

    @BeforeEach
    public void beforeEach() {
        rateLimiter = new RateLimiter();
    }

    @Test
    public void testRegister_SuccessNoParentSpan() {
        Key key = new Key();
        key.setRole("role");
        key.setKey("key");
        key.setProject("project");
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setOriginalKey(key);
        ProxyContext proxyContext = new ProxyContext(new Config(), request, apiKeyData, IdentityProvider.CLAIMS_WITH_EMPTY_ROLES, "trace-id", "span-id");

        assertFalse(rateLimiter.register(proxyContext));
        assertEquals("project", proxyContext.getOriginalProject());

        rateLimiter.unregister(proxyContext);

        // try to register again
        assertFalse(rateLimiter.register(proxyContext));
        assertEquals("project", proxyContext.getOriginalProject());
    }

    @Test
    public void testRegister_SuccessWithNullRole() {
        Key key = new Key();
        key.setKey("key");
        key.setProject("project");
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setOriginalKey(key);
        ProxyContext proxyContext = new ProxyContext(new Config(), request, apiKeyData, IdentityProvider.CLAIMS_WITH_EMPTY_ROLES, "trace-id", "span-id");

        assertFalse(rateLimiter.register(proxyContext));
        assertEquals("project", proxyContext.getOriginalProject());

        rateLimiter.unregister(proxyContext);

        // try to register again
        assertFalse(rateLimiter.register(proxyContext));
        assertEquals("project", proxyContext.getOriginalProject());
    }

    @Test
    public void testRegister_SuccessParentSpanExists() {
        Key key = new Key();
        key.setRole("role");
        key.setKey("key");
        key.setProject("project");
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setOriginalKey(key);
        ProxyContext proxyContext = new ProxyContext(new Config(), request, apiKeyData, IdentityProvider.CLAIMS_WITH_EMPTY_ROLES, "trace-id", "span-id");

        assertFalse(rateLimiter.register(proxyContext));
        assertEquals("project", proxyContext.getOriginalProject());

        assertTrue(rateLimiter.register(proxyContext));
        assertEquals("project", proxyContext.getOriginalProject());
    }

    @Test
    public void testLimit_EntityNotFound() {
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setOriginalKey(new Key());
        ProxyContext proxyContext = new ProxyContext(new Config(), request, apiKeyData, IdentityProvider.CLAIMS_WITH_EMPTY_ROLES, "unknown-trace-id", "span-id");

        RateLimitResult result = rateLimiter.limit(proxyContext);

        assertNotNull(result);
        assertEquals(HttpStatus.FORBIDDEN, result.status());
    }

    @Test
    public void testLimit_SuccessUser() {
        ProxyContext proxyContext = new ProxyContext(new Config(), request, new ApiKeyData(), new ExtractedClaims("sub", Collections.emptyList(), "hash"), "trace-id", "span-id");

        assertFalse(rateLimiter.register(proxyContext));

        RateLimitResult result = rateLimiter.limit(proxyContext);

        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.status());
    }

    @Test
    public void testLimit_ApiKeyLimitNotFound() {
        Key key = new Key();
        key.setRole("role");
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setOriginalKey(key);
        ProxyContext proxyContext = new ProxyContext(new Config(), request, apiKeyData, IdentityProvider.CLAIMS_WITH_EMPTY_ROLES, "trace-id", "span-id");
        proxyContext.setDeployment(new Model());


        assertFalse(rateLimiter.register(proxyContext));

        RateLimitResult result = rateLimiter.limit(proxyContext);

        assertNotNull(result);
        assertEquals(HttpStatus.FORBIDDEN, result.status());

    }


    @Test
    public void testLimit_ApiKeyLimitNotFoundWithNullRole() {
        Key key = new Key();
        key.setKey("key");
        key.setProject("project");
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setOriginalKey(key);
        ProxyContext proxyContext = new ProxyContext(new Config(), request, apiKeyData, IdentityProvider.CLAIMS_WITH_EMPTY_ROLES, "trace-id", "span-id");
        proxyContext.setDeployment(new Model());


        assertFalse(rateLimiter.register(proxyContext));

        RateLimitResult result = rateLimiter.limit(proxyContext);

        assertNotNull(result);
        assertEquals(HttpStatus.FORBIDDEN, result.status());

    }

    @Test
    public void testLimit_ApiKeyLimitNegative() {
        Key key = new Key();
        key.setRole("role");
        key.setKey("key");
        Config config = new Config();
        Role role = new Role();
        Limit limit = new Limit();
        limit.setDay(-1);
        role.setLimits(Map.of("model", limit));
        config.setRoles(Map.of("role", role));
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setOriginalKey(key);
        ProxyContext proxyContext = new ProxyContext(config, request, apiKeyData, IdentityProvider.CLAIMS_WITH_EMPTY_ROLES, "trace-id", "span-id");
        Model model = new Model();
        model.setName("model");
        proxyContext.setDeployment(model);


        assertFalse(rateLimiter.register(proxyContext));

        RateLimitResult result = rateLimiter.limit(proxyContext);

        assertNotNull(result);
        assertEquals(HttpStatus.FORBIDDEN, result.status());

    }

    @Test
    public void testLimit_ApiKeySuccess() {
        Key key = new Key();
        key.setRole("role");
        key.setKey("key");
        Config config = new Config();
        Role role = new Role();
        Limit limit = new Limit();
        role.setLimits(Map.of("model", limit));
        config.setRoles(Map.of("role", role));
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setOriginalKey(key);
        ProxyContext proxyContext = new ProxyContext(config, request, apiKeyData, IdentityProvider.CLAIMS_WITH_EMPTY_ROLES, "trace-id", "span-id");
        Model model = new Model();
        model.setName("model");
        proxyContext.setDeployment(model);

        assertFalse(rateLimiter.register(proxyContext));

        RateLimitResult result = rateLimiter.limit(proxyContext);

        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.status());

    }
}
