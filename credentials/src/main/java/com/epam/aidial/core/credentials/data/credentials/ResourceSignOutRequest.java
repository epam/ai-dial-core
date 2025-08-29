package com.epam.aidial.core.credentials.data.credentials;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.CredentialsLevel;
import lombok.Data;

@Data
public class ResourceSignOutRequest {

    private String url;
    private CredentialsLevel credentialsLevel;
    private AuthenticationType authenticationType;
}
