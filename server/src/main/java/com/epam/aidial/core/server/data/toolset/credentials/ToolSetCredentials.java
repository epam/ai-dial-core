package com.epam.aidial.core.server.data.toolset.credentials;

import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.ToolsetCredentialsStatus;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ToolSetCredentials {

    private String toolSetName;
    private CredentialsLevel credentialsLevel;
    private AuthenticationType authenticationType;
    //TODO: what to do with status?
    private ToolsetCredentialsStatus status;

    private String apiKeyHeader;
    private String apiKey;

    private String accessToken;
    private String refreshToken;
    private long createdAt;
    private long expiresIn;
}
