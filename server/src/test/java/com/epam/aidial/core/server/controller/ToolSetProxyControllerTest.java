package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.security.EncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolSetProxyControllerTest {

    @Mock
    private Proxy proxy;

    @Mock
    private EncryptionService encryptionService;

    private ProxyContext context;

    @BeforeEach
    void setUp() {
        when(proxy.getEncryptionService()).thenReturn(encryptionService);
        context = mock(ProxyContext.class, RETURNS_DEEP_STUBS);
        when(context.getProxy()).thenReturn(proxy);
        when(context.getApiKeyData()).thenReturn(mock(ApiKeyData.class));
        when(context.getUserSub()).thenReturn("userSub");
    }

    @ParameterizedTest
    @CsvSource({"toolset-1", "toolset 1"})
    void testCreateToolSetProxyController(String toolSetName) {
        // Given
        String toolSetId = "toolsets/encrypted-bucket/%s".formatted(toolSetName);
        when(encryptionService.decrypt("encrypted-bucket")).thenReturn("decrypted-bucket/");

        // When
        ToolSetProxyController toolSetProxyController = new ToolSetProxyController(proxy, context, toolSetId);

        // Then
        assertNotNull(toolSetProxyController);
    }

    // TODO: add more tests
}
