package com.epam.aidial.core.credentials.data.credentials;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.CredentialsLevel;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ResourceSignOutRequest {

    @NotBlank(message = "resource url should be specified")
    private String url;

    @NotNull(message = "credentialsLevel should be specified")
    @JsonAlias({"credentialsLevel", "credentials_level"})
    private CredentialsLevel credentialsLevel;

    @NotNull(message = "authenticationType should be specified")
    @JsonAlias({"authenticationType", "authentication_type"})
    private AuthenticationType authenticationType;
}
