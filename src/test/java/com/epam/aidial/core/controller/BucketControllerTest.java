package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.ApiKeyData;
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
import static org.mockito.Mockito.times;
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
    public void handle_RootInitiatorApp() {
        ApiKeyData apiKeyData = new ApiKeyData();
        when(context.getProject()).thenReturn("prj");
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(encryptionService.encrypt(anyString())).thenReturn("enc-prj");

        controller.getBucket();

        verify(encryptionService).encrypt("Keys/prj/");
        verify(context).respond(eq(HttpStatus.OK), argThat((ArgumentMatcher<Object>) arg -> {
            Bucket bucket = (Bucket) arg;
            return "enc-prj".equals(bucket.bucket()) && bucket.appdata() == null;
        }));
    }

    @Test
    public void handle_RootInitiatorUser() {
        ApiKeyData apiKeyData = new ApiKeyData();
        when(context.getUserSub()).thenReturn("sub");
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(encryptionService.encrypt(anyString())).thenReturn("enc-sub");

        controller.getBucket();

        verify(encryptionService).encrypt("Users/sub/");
        verify(context).respond(eq(HttpStatus.OK), argThat((ArgumentMatcher<Object>) arg -> {
            Bucket bucket = (Bucket) arg;
            return "enc-sub".equals(bucket.bucket()) && bucket.appdata() == null;
        }));
    }

    @Test
    public void handle_CallerApplication_ApiKey() {
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("key");
        when(context.getSourceDeployment()).thenReturn("app");
        when(context.getProject()).thenReturn("prj");
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(encryptionService.encrypt(anyString())).thenAnswer(invocation -> {
            String arg = invocation.getArgument(0);
            if (arg.endsWith("app/")) {
                return "enc-app";
            } else {
                return "enc-prj";
            }
        });

        controller.getBucket();

        verify(encryptionService, times(2))
                .encrypt(argThat(argument -> "Keys/app/".equals(argument) || "Keys/prj/".equals(argument)));
        verify(context).respond(eq(HttpStatus.OK), argThat((ArgumentMatcher<Object>) arg -> {
            Bucket bucket = (Bucket) arg;
            return "enc-app".equals(bucket.bucket()) && "enc-prj/appdata/app".equals(bucket.appdata());
        }));
    }

    @Test
    public void handle_CallerApplication_Jwt() {
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("key");
        when(context.getSourceDeployment()).thenReturn("app");
        when(context.getUserSub()).thenReturn("sub");
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(encryptionService.encrypt(anyString())).thenAnswer(invocation -> {
            String arg = invocation.getArgument(0);
            if (arg.endsWith("app/")) {
                return "enc-app";
            } else {
                return "enc-sub";
            }
        });

        controller.getBucket();

        verify(encryptionService, times(2))
                .encrypt(argThat(argument -> "Keys/app/".equals(argument) || "Users/sub/".equals(argument)));
        verify(context).respond(eq(HttpStatus.OK), argThat((ArgumentMatcher<Object>) arg -> {
            Bucket bucket = (Bucket) arg;
            return "enc-app".equals(bucket.bucket()) && "enc-sub/appdata/app".equals(bucket.appdata());
        }));
    }
}
