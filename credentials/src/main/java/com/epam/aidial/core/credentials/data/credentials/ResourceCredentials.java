package com.epam.aidial.core.credentials.data.credentials;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.CredentialsLevel;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

@Data
@Builder
@Jacksonized
public class ResourceCredentials {

    private String resourceId;
    private CredentialsLevel credentialsLevel;
    private AuthenticationType authenticationType;
    private String apiKeyHeader;
    private String apiKey;
    private String accessToken;
    private String refreshToken;
    private long createdAt;
    private long updatedAt;
    private Long expiresInSeconds;
    private String userSub;
}
