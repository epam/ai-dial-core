package com.epam.aidial.core.config;

import lombok.Data;

@Data
public class ToolSetSignOutRequest {

    private String toolsetUrl;
    private CredentialsLevel credentialsLevel;
    private AuthenticationType authenticationType;
}
