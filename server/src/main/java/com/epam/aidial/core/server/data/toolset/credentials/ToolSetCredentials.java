package com.epam.aidial.core.server.data.toolset.credentials;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.CredentialsLevel;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

@Data
@Builder
@Jacksonized
public class ToolSetCredentials {

    private String toolSetName;
    private CredentialsLevel credentialsLevel;
    private AuthenticationType authenticationType;
    private String apiKeyHeader;
    private String apiKey;
    private String accessToken;
    private String refreshToken;
    private long createdAt;
    private long updatedAt;
    private long expiresIn;
    private String userSub;

    @JsonIgnore
    public boolean isTokenExpired() {
        if (authenticationType.equals(AuthenticationType.OAUTH)) {
            return updatedAt + expiresIn * 1000 <= System.currentTimeMillis();
        }
        return true;
    }
}
