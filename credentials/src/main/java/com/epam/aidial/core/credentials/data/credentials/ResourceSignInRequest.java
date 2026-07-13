package com.epam.aidial.core.credentials.data.credentials;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.CredentialsLevel;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

@Data
@Builder
@Jacksonized
public class ResourceSignInRequest {

    @NotBlank(message = "resource url should be specified")
    private String url;

    @NotNull(message = "credentialsLevel should be specified")
    @JsonAlias({"credentialsLevel", "credentials_level"})
    private CredentialsLevel credentialsLevel;

    @NotNull(message = "authenticationType should be specified")
    @JsonAlias({"authenticationType", "authentication_type"})
    private AuthenticationType authenticationType;

    private String code;

    @JsonAlias({"apiKey", "api_key"})
    private String apiKey;

    @JsonAlias({"redirectUri", "redirect_uri"})
    private String redirectUri;

    // Owner opts in to offline (on-behalf-of) use of the stored credential. Required for the OBO retrieval path.
    @JsonAlias({"offlineUsageConsent", "offline_usage_consent"})
    private boolean offlineUsageConsent;
}
