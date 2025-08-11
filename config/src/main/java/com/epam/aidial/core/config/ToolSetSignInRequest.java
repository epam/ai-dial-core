package com.epam.aidial.core.config;

import lombok.Data;

@Data
public class ToolSetSignInRequest {

    private CredentialsLevel credentialsLevel;
    private ToolsetAuthenticationType authenticationType;
    private String code;
    private String apiKey;
}
