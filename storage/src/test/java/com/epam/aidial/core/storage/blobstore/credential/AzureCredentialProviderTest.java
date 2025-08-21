package com.epam.aidial.core.storage.blobstore.credential;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DefaultAzureCredential;
import org.jclouds.domain.Credentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class AzureCredentialProviderTest {

    @Mock
    private DefaultAzureCredential defaultAzureCredential;

    @Mock
    private TokenRequestContext tokenRequestContext;

    @Mock
    private Supplier<OffsetDateTime> now;

    @InjectMocks
    private AzureCredentialProvider provider;

    @Test
    public void testGetTempCredentials() {
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
        when(defaultAzureCredential.getTokenSync(eq(tokenRequestContext))).thenAnswer(invocation -> {
            int next = count.incrementAndGet();
            OffsetDateTime expired;
            long ts;
            if (next == 1) {
                ts = 20;
            } else {
                ts = 30;
            }
            expired = to(ts);
            return new AccessToken(Integer.toString(next), expired);
        });
        Credentials credentials = provider.getCredentials();
        assertNotNull(credentials);
        assertEquals("1", credentials.credential);
        credentials = provider.getCredentials();
        assertNotNull(credentials);
        assertEquals("2", credentials.credential);
    }

    private static OffsetDateTime to(long epochSecond) {
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSecond), ZoneId.of("UTC"));
    }
}
