package com.epam.aidial.core.config;

import lombok.Data;

@Data
public class ToolSetSignInRequest {

    private String toolSetUrl;
    private CredentialsLevel credentialsLevel;
    private ToolsetAuthenticationType authenticationType;
    private String code;
    private String scope;
    private String apiKey;
}
