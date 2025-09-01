package com.epam.aidial.core.credentials.data.credentials;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import lombok.Data;

@Data
public class ResourceSignOutRequest {

    private ResourceDescriptor resourceDescriptor;
    private CredentialsLevel credentialsLevel;
    private AuthenticationType authenticationType;
}
