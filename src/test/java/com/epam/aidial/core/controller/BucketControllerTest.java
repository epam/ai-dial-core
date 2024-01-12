package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.ApiKeyData;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.data.Bucket;
import com.epam.aidial.core.security.EncryptionService;
import com.epam.aidial.core.util.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class BucketControllerTest {
    @Mock
    private ProxyContext context;

    @Mock
    private Proxy proxy;

    @Mock
    private EncryptionService encryptionService;

    @InjectMocks
    private BucketController controller;

    @BeforeEach
    public void beforeEach() {
        when(proxy.getEncryptionService()).thenReturn(encryptionService);
    }

    @Test
    public void handle_RootInitiator() {
        ApiKeyData apiKeyData = new ApiKeyData();
        when(context.getProject()).thenReturn("prj");
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(encryptionService.encrypt(anyString())).thenReturn("enc-prj");

        controller.getBucket();

        verify(context).respond(eq(HttpStatus.OK), argThat((ArgumentMatcher<Object>) arg -> {
            Bucket bucket = (Bucket) arg;
            return "enc-prj".equals(bucket.bucket()) && bucket.appdata() == null;
        }));
    }
}
