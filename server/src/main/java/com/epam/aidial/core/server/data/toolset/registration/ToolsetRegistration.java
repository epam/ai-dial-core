package com.epam.aidial.core.server.data.toolset.registration;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ToolsetRegistration {

    private String toolSetName;
    private String clientId;
    private String clientSecret;
    private String scope;
    private String authorizationEndpoint;
    private String tokenEndpoint;
    private String redirectUri;
}
