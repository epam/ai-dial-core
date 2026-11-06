package com.epam.aidial.core.server.function;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.storage.http.HttpException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class CollectRequestSkillsFnTest {

    @Mock
    private Proxy proxy;

    @Mock
    private ProxyContext context;

    @Mock
    private AccessService accessService;

    @Mock
    private EncryptionService encryptionService;

    @InjectMocks
    private CollectRequestSkillsFn fn;

    private RequestObject request;

    @BeforeEach
    void setUp() {
        request = mock(RequestObject.class);
    }

    @Test
    void apply_appendsSkillToApiKeyData_whenSkillIsReadable() {
        String skillUrl = "skills/bucket/my-skill";
        when(request.collectSkills()).thenReturn(Set.of(skillUrl));
        when(proxy.getEncryptionService()).thenReturn(encryptionService);
        when(encryptionService.decrypt("bucket")).thenReturn("location/");
        when(proxy.getAccessService()).thenReturn(accessService);
        when(accessService.hasReadAccess(any(), any())).thenReturn(true);
        ApiKeyData apiKeyData = new ApiKeyData();
        when(context.getProxyApiKeyData()).thenReturn(apiKeyData);

        boolean result = fn.apply(request);

        assertFalse(result);
        assertNotNull(apiKeyData.getAttachedSkills().get(skillUrl));
        assertEquals(1, apiKeyData.getAttachedSkills().size());
    }

    @Test
    void apply_doesNotAppendSkill_whenSkillIsPublic() {
        String skillUrl = "skills/public/my-skill";
        when(request.collectSkills()).thenReturn(Set.of(skillUrl));
        when(proxy.getEncryptionService()).thenReturn(encryptionService);

        boolean result = fn.apply(request);

        assertFalse(result);
    }

    @Test
    void apply_throws_whenAccessServiceHasNoReadAccess() {
        String skillUrl = "skills/bucket/my-skill";
        when(request.collectSkills()).thenReturn(Set.of(skillUrl));
        when(proxy.getEncryptionService()).thenReturn(encryptionService);
        when(encryptionService.decrypt("bucket")).thenReturn("location/");
        when(proxy.getAccessService()).thenReturn(accessService);
        when(accessService.hasReadAccess(any(), any())).thenReturn(false);

        HttpException error = Assertions.assertThrows(HttpException.class, () -> fn.apply(request));

        assertEquals(403, error.getStatus().getCode());
    }

    @Test
    void apply_throws_whenUrlIsNotSkillResource() {
        String fileUrl = "files/bucket/my-file";
        when(request.collectSkills()).thenReturn(Set.of(fileUrl));
        when(proxy.getEncryptionService()).thenReturn(encryptionService);
        when(encryptionService.decrypt("bucket")).thenReturn("location/");

        HttpException error = Assertions.assertThrows(HttpException.class, () -> fn.apply(request));

        assertEquals(400, error.getStatus().getCode());
    }

    @Test
    void apply_throws_whenUrlIsAbsolute() {
        String url = "http://example.com/skill";
        when(request.collectSkills()).thenReturn(Set.of(url));

        HttpException error = Assertions.assertThrows(HttpException.class, () -> fn.apply(request));

        assertEquals(400, error.getStatus().getCode());
    }
}
