package com.epam.aidial.core.server.service.credentials;

import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.config.ToolSetAuthSettings;
import com.epam.aidial.core.server.data.toolset.registration.ClientRegistrationRequest;
import com.epam.aidial.core.server.data.toolset.registration.ClientRegistrationResponse;
import com.epam.aidial.core.server.data.toolset.registration.ToolSetAuthorizationServerMetadata;
import com.epam.aidial.core.server.data.toolset.registration.ToolSetAuthorizationServerProtectedResourceMetadata;
import com.epam.aidial.core.server.data.toolset.registration.ToolSetRegistration;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.http.ContentType;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class ToolSetRegistrationService {
    private static final String AUTH_SERVER_ENDPOINT = "%s/.well-known/oauth-authorization-server";
    private static final String PROTECTED_RESOURCE_ENDPOINT = "%s/.well-known/oauth-protected-resource";
    private static final String AUTHORIZE_ENDPOINT = "%s/authorize";
    private static final String TOKEN_ENDPOINT = "%s/token";

    private final ToolSetAuthorizationServerClient toolSetAuthorizationServerClient;

    public ToolSetRegistration createDynamicToolSetRegistration(ToolSet toolSet) {
        String toolSetName = toolSet.getName();
        log.info("Start ToolSet: {} registration.", toolSetName);

        String baseToolSetEndpoint = getBaseToolSetEndpoint(toolSet);
        String toolSetAuthServerEndpoint = getToolSetAuthorizationServerEndpoint(baseToolSetEndpoint);
        ToolSetAuthorizationServerMetadata toolsetAuthorizationServerMetadata = getToolsetAuthorizationServerMetadata(toolSetAuthServerEndpoint);
        String toolSetRedirectUri = getToolSetRedirectUri(toolSet);

        ClientRegistrationRequest clientRegistrationRequest = ClientRegistrationRequest.builder()
                .clientName(toolSetName)
                .redirectUris(List.of(toolSetRedirectUri))
                .build();

        ClientRegistrationResponse clientRegistrationResponse = toolSetAuthorizationServerClient.executePost(
                toolsetAuthorizationServerMetadata.getRegistrationEndpoint(),
                clientRegistrationRequest,
                ContentType.APPLICATION_JSON.toString(),
                ClientRegistrationResponse.class);

        ToolSetRegistration toolsetRegistration = createToolsetRegistration(
                clientRegistrationResponse,
                toolsetAuthorizationServerMetadata);

        log.info("Finished ToolSet {} registration.", toolSetName);
        return toolsetRegistration;
    }

    public ToolSetRegistration createStaticToolSetRegistration(ToolSet toolSet) {
        ToolSetAuthSettings toolSetAuthSettings = toolSet.getAuthSettings();
        String baseToolSetEndpoint = getBaseToolSetEndpoint(toolSet);
        String toolSetAuthServerEndpoint = getToolSetAuthorizationServerEndpoint(baseToolSetEndpoint);
        String toolSetAuthorizationEndpoint;
        String tokenEndpoint;
        String codeChallengeMethodSupported;
        try {
            ToolSetAuthorizationServerMetadata authorizationServerMetadata = getToolsetAuthorizationServerMetadata(toolSetAuthServerEndpoint);
            toolSetAuthorizationEndpoint = authorizationServerMetadata.getAuthorizationEndpoint();
            tokenEndpoint = authorizationServerMetadata.getTokenEndpoint();
            codeChallengeMethodSupported = getCodeChallengeMethod(authorizationServerMetadata);
        } catch (HttpException e) {
            log.error(e.getMessage(), e);
            toolSetAuthorizationEndpoint = String.format(AUTHORIZE_ENDPOINT, baseToolSetEndpoint);
            tokenEndpoint = String.format(TOKEN_ENDPOINT, baseToolSetEndpoint);
            codeChallengeMethodSupported = CodeChallengeMethod.S256.getValue();
        }

        return ToolSetRegistration.builder()
            .toolSetName(toolSet.getName())
            .clientId(toolSetAuthSettings.getClientId())
            .clientSecret(toolSetAuthSettings.getClientSecret())
            .redirectUri(toolSetAuthSettings.getRedirectUri())
            .authorizationEndpoint(toolSetAuthorizationEndpoint)
            .tokenEndpoint(tokenEndpoint)
            .codeChallengeMethod(codeChallengeMethodSupported)
            .build();
    }

    private String getBaseToolSetEndpoint(ToolSet toolSet) {
        String toolSetEndpoint = toolSet.getEndpoint();
        String baseToolSetEndpoint = toolSetEndpoint.endsWith("/mcp")
                                     ? toolSetEndpoint.substring(0, toolSetEndpoint.lastIndexOf("/mcp"))
                                     : toolSetEndpoint;
        log.debug("ToolSet {} base endpoint: {}", toolSet.getName(), baseToolSetEndpoint);
        return baseToolSetEndpoint;
    }

    private String getToolSetRedirectUri(ToolSet toolSet) {
        ToolSetAuthSettings toolsetAuthSettings = toolSet.getAuthSettings();
        String toolSetRedirectUri = toolsetAuthSettings.getRedirectUri();
        log.debug("ToolSet {} RedirectUri: {}", toolSet.getName(), toolSetRedirectUri);
        return toolSetRedirectUri;
    }

    private ToolSetRegistration createToolsetRegistration(ClientRegistrationResponse clientRegistrationResponse,
                                                          ToolSetAuthorizationServerMetadata toolsetAuthorizationServerMetadata) {
        return ToolSetRegistration.builder()
            .toolSetName(clientRegistrationResponse.getClientName())
            .clientId(clientRegistrationResponse.getClientId())
            .clientSecret(clientRegistrationResponse.getClientSecret())
            .redirectUri(clientRegistrationResponse.getRedirectUris().getFirst())
            .authorizationEndpoint(toolsetAuthorizationServerMetadata.getAuthorizationEndpoint())
            .tokenEndpoint(toolsetAuthorizationServerMetadata.getTokenEndpoint())
            .codeChallengeMethod(getCodeChallengeMethod(toolsetAuthorizationServerMetadata))
            .build();
    }

    //TODO: change default method to S256
    private String getCodeChallengeMethod(ToolSetAuthorizationServerMetadata toolsetAuthorizationServerMetadata) {
        List<String> codeChallengeMethodsSupported = toolsetAuthorizationServerMetadata.getCodeChallengeMethodsSupported();
        return codeChallengeMethodsSupported.contains(CodeChallengeMethod.PLAIN.getValue())
                                     ? CodeChallengeMethod.PLAIN.getValue()
                                     : codeChallengeMethodsSupported.getFirst();
    }

    private String getToolSetAuthorizationServerEndpoint(String baseToolSetEndpoint) {
        String toolSetAuthServerBaseEndpoint;
        try {
            ToolSetAuthorizationServerProtectedResourceMetadata toolsetAuthorizationServerProtectedResourceMetadata =
                    getToolsetAuthorizationServerProtectedResourceMetadata(baseToolSetEndpoint);

            List<String> authorizationServers = toolsetAuthorizationServerProtectedResourceMetadata.getAuthorizationServers();
            if (authorizationServers == null || authorizationServers.isEmpty()) {
                throw new RuntimeException("No authorization servers defined for dynamic client registration.");
            }
            //TODO: should we get the first one?
            toolSetAuthServerBaseEndpoint = toolsetAuthorizationServerProtectedResourceMetadata.getAuthorizationServers().getFirst();
        } catch (HttpException e) {
            if (e.getStatus().equals(HttpStatus.NOT_FOUND)) {
                toolSetAuthServerBaseEndpoint = baseToolSetEndpoint;
            } else {
                log.error(e.getMessage(), e);
                throw new RuntimeException("Error getting authorization servers for dynamic client registration.");
            }
        }
        String toolSetAuthServerWellKnownEndpoint = String.format(AUTH_SERVER_ENDPOINT, toolSetAuthServerBaseEndpoint);
        log.debug("ToolSetAuthServerEndpoint: {}", toolSetAuthServerWellKnownEndpoint);
        return toolSetAuthServerWellKnownEndpoint;
    }

    private ToolSetAuthorizationServerMetadata getToolsetAuthorizationServerMetadata(String toolSetAuthServerEndpoint) {
        ToolSetAuthorizationServerMetadata toolSetAuthorizationServerMetadata = toolSetAuthorizationServerClient.executeGet(toolSetAuthServerEndpoint,
                ToolSetAuthorizationServerMetadata.class);
        log.debug("ToolSetAuthorizationServerMetadata: {}", toolSetAuthorizationServerMetadata);
        return toolSetAuthorizationServerMetadata;
    }

    private ToolSetAuthorizationServerProtectedResourceMetadata getToolsetAuthorizationServerProtectedResourceMetadata(String baseToolSetEndpoint) {
        String toolSetProtectedResourceEndpoint = String.format(PROTECTED_RESOURCE_ENDPOINT, baseToolSetEndpoint);
        ToolSetAuthorizationServerProtectedResourceMetadata toolsetAuthorizationServerProtectedResourceMetadata =
                toolSetAuthorizationServerClient.executeGet(toolSetProtectedResourceEndpoint, ToolSetAuthorizationServerProtectedResourceMetadata.class);
        log.debug("ToolSetAuthorizationServerProtectedResourceMetadata: {}", toolsetAuthorizationServerProtectedResourceMetadata);
        return toolsetAuthorizationServerProtectedResourceMetadata;
    }
}

