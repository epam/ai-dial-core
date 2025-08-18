package com.epam.aidial.core.config;

import lombok.Data;

@Data
public class ToolSetSignInRequest {

    private String url;
    private CredentialsLevel credentialsLevel;
    private AuthenticationType authenticationType;
    private String code;
    private String apiKey;
}
