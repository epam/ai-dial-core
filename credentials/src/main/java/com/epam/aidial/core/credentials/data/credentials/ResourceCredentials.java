package com.epam.aidial.core.credentials.data.credentials;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.CredentialsLevel;
import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;

@Data
@Builder
@Jacksonized
public class ResourceCredentials {

    private String resourceId;
    private CredentialsLevel credentialsLevel;
    private AuthenticationType authenticationType;
    private String apiKeyHeader;
    @ToString.Exclude
    private String apiKey;
    @ToString.Exclude
    private String accessToken;
    @ToString.Exclude
    private String refreshToken;
    private long createdAt;
    private long updatedAt;
    private Long expiresInSeconds;
    @JsonAlias({"userSub", "userId"})
    private String userId;
    // Owner's recorded consent to offline (on-behalf-of) use of this credential. Legacy blobs deserialize as false.
    @JsonAlias({"offlineUsageConsent", "offline_usage_consent"})
    private boolean offlineUsageConsent;
}
