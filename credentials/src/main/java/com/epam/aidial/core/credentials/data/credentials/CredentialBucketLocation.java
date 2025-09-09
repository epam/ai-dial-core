package com.epam.aidial.core.credentials.data.credentials;

import com.epam.aidial.core.config.CredentialsLevel;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CredentialBucketLocation {
    private CredentialsLevel credentialsLevel;
    private String bucketName;
    private String bucketLocation;
}
