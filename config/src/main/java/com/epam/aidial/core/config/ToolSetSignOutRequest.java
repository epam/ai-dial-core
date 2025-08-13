package com.epam.aidial.core.config;

import lombok.Data;

@Data
public class ToolSetSignOutRequest {

    private String toolSetUrl;
    private CredentialsLevel credentialsLevel;
    private ToolsetAuthenticationType authenticationType;
}
