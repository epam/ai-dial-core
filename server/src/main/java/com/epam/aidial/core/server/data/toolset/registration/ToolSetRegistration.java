package com.epam.aidial.core.server.data.toolset.registration;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ToolSetRegistration {

    private String toolSetName;
    private String clientId;
    private String clientSecret;
    private String authorizationEndpoint;
    private String tokenEndpoint;
    private String redirectUri;
    private String codeChallengeMethod;
}
