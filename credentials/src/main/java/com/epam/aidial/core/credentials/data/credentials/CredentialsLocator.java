package com.epam.aidial.core.credentials.data.credentials;

import com.epam.aidial.core.storage.resource.ResourceType;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CredentialsLocator {

    private String resourceId;

    private ResourceType type;
    private SourceType sourceType;
    private String name;
    private List<String> parentFolders;
    private List<CredentialBucketLocation> buckets;

}
