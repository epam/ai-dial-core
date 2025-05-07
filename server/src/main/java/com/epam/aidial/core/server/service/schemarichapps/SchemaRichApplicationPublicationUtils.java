package com.epam.aidial.core.server.service.schemarichapps;

import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import lombok.experimental.UtilityClass;

@UtilityClass
public class SchemaRichApplicationPublicationUtils {
    public static String getTargetFolderForCustomAppFiles(String targetUrl, EncryptionService encryptionService) {
        ResourceDescriptor targetResourceDescriptor = ResourceDescriptorFactory.fromAnyUrl(targetUrl, encryptionService);
        if (targetResourceDescriptor.isFolder()) {
            throw new IllegalArgumentException("Target url must be a file");
        }
        if (targetResourceDescriptor.getType() != ResourceTypes.APPLICATION) {
            throw new IllegalArgumentException("Target url must be an application type");
        }
        String appName = targetResourceDescriptor.getName();
        String appPath = targetResourceDescriptor.getParentPath();
        if (appPath == null) {
            return "." + appName + ResourceDescriptor.PATH_SEPARATOR;
        } else {
            return appPath + ResourceDescriptor.PATH_SEPARATOR + "." + appName + ResourceDescriptor.PATH_SEPARATOR;
        }
    }
}
