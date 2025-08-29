package com.epam.aidial.core.credentials.data.registration;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ClientRegistration {

    private String resourceId;
    private String clientId;
    private String clientSecret;
    private String authorizationEndpoint;
    private String tokenEndpoint;
    private String redirectUri;
    private String codeChallengeMethod;
}
