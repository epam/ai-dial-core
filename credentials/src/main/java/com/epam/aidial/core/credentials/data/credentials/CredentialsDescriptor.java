package com.epam.aidial.core.credentials.data.credentials;

import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.storage.resource.ResourceType;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CredentialsDescriptor {

    private static final String PATH_SEPARATOR = "/";

    private String resourceId;

    private ResourceType type;
    private SourceType sourceType;
    private String name;
    private List<String> parentFolders;
    private String bucketName;
    private String bucketLocation;
    private CredentialsLevel credentialsLevel;

    public String getDecodedPath() {
        StringBuilder builder = new StringBuilder();
        builder.append(bucketLocation)
                .append(type.group())
                .append(PATH_SEPARATOR)
                .append(sourceType)
                .append(PATH_SEPARATOR);

        if (!parentFolders.isEmpty()) {
            builder.append(String.join(PATH_SEPARATOR, parentFolders))
                    .append(PATH_SEPARATOR);
        }

        builder.append(name);

        return builder.toString();
    }

}
