package com.epam.aidial.core.server.data.toolset.credentials;

import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.ToolsetAuthenticationType;
import com.epam.aidial.core.config.ToolsetCredentialsStatus;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ToolSetCredentials {

    private String toolSetName;
    private CredentialsLevel credentialsLevel;
    private ToolsetAuthenticationType toolsetAuthenticationType;
    private ToolsetCredentialsStatus status;

    // TODO: do we need apiKeyHeader in cred?
    private String apiKeyHeader;
    private String apiKey;

    private String accessToken;
    private String refreshToken;
}
