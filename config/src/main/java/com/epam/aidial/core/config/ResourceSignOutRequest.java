package com.epam.aidial.core.config;

import lombok.Data;

@Data
public class ResourceSignOutRequest {

    private String url;
    private CredentialsLevel credentialsLevel;
    private AuthenticationType authenticationType;
}
