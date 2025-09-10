package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsService;
import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ToolSetServiceTest {

    @Mock
    private ResourceService resourceService;

    @Mock
    private ResourceAuthSettingsService resourceAuthSettingsService;

    @InjectMocks
    private ToolSetService toolSetService;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
    }


    private static Stream<Arguments> provideToolSetScenariosForEnrichment() {
        return Stream.of(
                // Endpoint changed
                Arguments.of(
                        createToolSet("updatedEndpoint", AuthenticationType.OAUTH),
                        createToolSet("existingEndpoint", AuthenticationType.OAUTH),
                        true
                ),

                // Auth type changed
                Arguments.of(
                        createToolSet("existingEndpoint", AuthenticationType.OAUTH),
                        createToolSet("existingEndpoint", AuthenticationType.NONE),
                        true
                ),

                // New ToolSet
                Arguments.of(
                        createToolSet("existingEndpoint", AuthenticationType.OAUTH),
                        null,
                        true
                ),

                // Neither endpoint nor auth type changed
                Arguments.of(
                        createToolSet("existingEndpoint", AuthenticationType.OAUTH),
                        createToolSet("existingEndpoint", AuthenticationType.OAUTH),
                        false
                )
        );
    }

    @ParameterizedTest
    @MethodSource("provideToolSetScenariosForEnrichment")
    void testPutToolSet_WhenEnrichmentRequired(
            ToolSet updatedToolSet,
            ToolSet existingToolSet,
            boolean shouldEnrichAuthSettings
    ) {
        // Given
        String expectedOutputJson = "expectedOutputJson";

        MockedStatic<ProxyUtil> proxyUtil = Mockito.mockStatic(ProxyUtil.class);
        proxyUtil.when(() -> ProxyUtil.convertToObject(any(String.class), eq(ToolSet.class)))
                .thenReturn(existingToolSet);
        proxyUtil.when(() -> ProxyUtil.convertToString(any()))
                .thenReturn(expectedOutputJson);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Function<String, String>> lambdaCaptor = ArgumentCaptor.forClass(Function.class);

        EtagHeader etag = EtagHeader.ANY;
        String author = "author";

        ResourceDescriptor resource = mock(ResourceDescriptor.class);
        when(resource.getUrl()).thenReturn("url");
        when(resource.getType()).thenReturn(ResourceTypes.TOOL_SET);

        when(resourceService.computeResource(
                eq(resource), eq(etag), eq(author), lambdaCaptor.capture())
        ).thenReturn(mock(ResourceItemMetadata.class));

        // When
        toolSetService.putToolSet(resource, etag, author, updatedToolSet);
        String result = lambdaCaptor.getValue().apply("inputJson");

        // Then
        if (shouldEnrichAuthSettings) {
            verify(resourceAuthSettingsService).enrichResourceAuthSettings(updatedToolSet.getName(), updatedToolSet.getEndpoint(), updatedToolSet.getAuthSettings());
        } else {
            verifyNoInteractions(resourceAuthSettingsService);
        }

        assertEquals(expectedOutputJson, result);
        proxyUtil.close();
    }

    private static ToolSet createToolSet(String endpoint, AuthenticationType authenticationType) {
        ToolSet toolSet = new ToolSet();
        toolSet.setEndpoint(endpoint);

        ResourceAuthSettings updatedToolSetResourceAuthSettings = mock(ResourceAuthSettings.class);
        when(updatedToolSetResourceAuthSettings.getAuthenticationType()).thenReturn(authenticationType);
        toolSet.setAuthSettings(updatedToolSetResourceAuthSettings);

        return toolSet;
    }
}
