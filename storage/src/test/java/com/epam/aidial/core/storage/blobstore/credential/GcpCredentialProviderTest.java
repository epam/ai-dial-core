package com.epam.aidial.core.storage.blobstore.credential;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import org.jclouds.domain.Credentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class GcpCredentialProviderTest {
    @Mock
    private GoogleCredentials googleCredentials;

    @Mock
    private LongSupplier clock;

    @InjectMocks
    private GcpCredentialProvider provider;

    @Test
    public void testGetTempCredentials() throws IOException {
        AtomicInteger count = new AtomicInteger();
        when(clock.getAsLong()).thenAnswer(invocation -> {
            int current = count.get();
            if (current == 0) {
                return 15L;
            } else {
                return 25L;
            }
        });
        when(googleCredentials.refreshAccessToken()).thenAnswer(invocation -> {
            int next = count.incrementAndGet();
            long expired;
            if (next == 1) {
                expired = 20;
            } else {
                expired = 30;
            }
            return new AccessToken(Integer.toString(next), new Date(expired));
        });
        Credentials credentials = provider.getCredentials();
        assertNotNull(credentials);
        assertEquals("1", credentials.credential);
        credentials = provider.getCredentials();
        assertNotNull(credentials);
        assertEquals("2", credentials.credential);
    }
}
