package com.epam.aidial.core.server.service.toolset.registration;

import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.server.data.toolset.registration.ToolsetAuthorizationServerMetadata;
import com.epam.aidial.core.server.data.toolset.registration.ToolsetAuthorizationServerProtectedResourceMetadata;
import com.epam.aidial.core.server.data.toolset.registration.ClientRegistrationRequest;
import com.epam.aidial.core.server.data.toolset.registration.ClientRegistrationResponse;
import com.epam.aidial.core.server.data.toolset.registration.ToolsetRegistration;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class ToolsetRegistrationService {
    private static final String AUTH_SERVER_ENDPOINT = "%s/.well-known/oauth-authorization-server";
    private static final String PROTECTED_RESOURCE_ENDPOINT = "%s/.well-known/oauth-protected-resource";

    private final ToolsetAuthorizationServerClient toolsetAuthorizationServerClient;

    public ToolsetRegistration registerToolset(ToolSet toolSet) {
        String toolSetName = toolSet.getName();
        log.info("Start toolset registration: {}.", toolSetName);
        String toolSetEndpoint = toolSet.getEndpoint();
        String toolSetAuthServerEndpoint = null;
        try {
            String toolSetProtectedResourceEndpoint = String.format(PROTECTED_RESOURCE_ENDPOINT, toolSetEndpoint);
            ToolsetAuthorizationServerProtectedResourceMetadata toolsetAuthorizationServerProtectedResourceMetadata =
                toolsetAuthorizationServerClient.executeGet(toolSetProtectedResourceEndpoint, ToolsetAuthorizationServerProtectedResourceMetadata.class);
            toolSetAuthServerEndpoint = toolsetAuthorizationServerProtectedResourceMetadata.getAuthorizationServers().get(0);
            log.debug("ToolSetAuthServerEndpoint: {}", toolSetAuthServerEndpoint);
        } catch (HttpException e) {
            if (e.getStatus().equals(HttpStatus.NOT_FOUND)) {
                toolSetAuthServerEndpoint = String.format(AUTH_SERVER_ENDPOINT, toolSetEndpoint);
            }
        }
        ToolsetAuthorizationServerMetadata toolsetAuthorizationServerMetadata =
            toolsetAuthorizationServerClient.executeGet(toolSetAuthServerEndpoint, ToolsetAuthorizationServerMetadata.class);

        String registrationEndpoint = toolsetAuthorizationServerMetadata.getRegistrationEndpoint();
        log.debug("ToolSetRegistrationEndpoint: {}", registrationEndpoint);

        // TODO: where to get redirectUri?
        ClientRegistrationRequest clientRegistrationRequest = ClientRegistrationRequest.builder()
            .clientName(toolSet.getName())
            .redirectUris(List.of("http//:localhost:8080"))
            .build();

        ClientRegistrationResponse clientRegistrationResponse = toolsetAuthorizationServerClient.executePost(registrationEndpoint, clientRegistrationRequest, ClientRegistrationResponse.class);
        ToolsetRegistration toolsetRegistration = createToolsetRegistration(
            clientRegistrationResponse,
            toolsetAuthorizationServerMetadata);

        log.info("Finished toolset {} registration.", toolSet.getName());
        return toolsetRegistration;
    }

    private ToolsetRegistration createToolsetRegistration(ClientRegistrationResponse clientRegistrationResponse,
                                                          ToolsetAuthorizationServerMetadata toolsetAuthorizationServerMetadata) {
        return ToolsetRegistration.builder()
            .toolSetName(clientRegistrationResponse.getClientName())
            .clientId(clientRegistrationResponse.getClientId())
            .clientSecret(clientRegistrationResponse.getClientSecret())
            .redirectUri(clientRegistrationResponse.getRedirectUris().get(0))
            .scope(clientRegistrationResponse.getScope())
            .authorizationEndpoint(toolsetAuthorizationServerMetadata.getAuthorizationEndpoint())
            .tokenEndpoint(toolsetAuthorizationServerMetadata.getTokenEndpoint())
            .build();
    }
}

