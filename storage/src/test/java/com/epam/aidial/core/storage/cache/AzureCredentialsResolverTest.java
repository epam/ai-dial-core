package com.epam.aidial.core.storage.cache;

import com.azure.core.credential.AccessToken;
import com.azure.identity.DefaultAzureCredential;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.config.Credentials;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class AzureCredentialsResolverTest {

    @Mock
    private DefaultAzureCredential defaultAzureCredential;

    @Mock
    private Supplier<OffsetDateTime> now;

    @InjectMocks
    private AzureCredentialsResolver resolver;

    @Test
    public void testResolve() {
        AtomicInteger count = new AtomicInteger();
        when(now.get()).thenAnswer(invocation -> {
            int current = count.get();
            long now;
            if (current == 0) {
                now = 15;
            } else {
                now = 25;
            }
            return to(now);
        });
        when(defaultAzureCredential.getTokenSync(eq(AzureCredentialsResolver.TOKEN_REQUEST_CONTEXT))).thenAnswer(invocation -> {
            int next = count.incrementAndGet();
            OffsetDateTime expired;
            long ts;
            if (next == 1) {
                ts = 20;
            } else {
                ts = 30;
            }
            expired = to(ts);
            byte[] payload = ("{\"oid\":\"" + next + "\"}").getBytes(StandardCharsets.UTF_8);
            String token = "header." + new String(Base64.getEncoder().encode(payload)) + ".signature";
            return new AccessToken(token, expired);
        });
        Credentials credentials = resolver.resolve();
        assertNotNull(credentials);
        assertEquals("1", credentials.getUsername());
        assertNotNull(credentials.getPassword());
        credentials = resolver.resolve();
        assertNotNull(credentials);
        assertEquals("2", credentials.getUsername());
        assertNotNull(credentials.getPassword());
    }

    private static OffsetDateTime to(long epochSecond) {
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSecond), ZoneId.of("UTC"));
    }
}
