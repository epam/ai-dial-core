package com.epam.aidial.core.credentials.data.credentials;

import com.epam.aidial.core.config.CredentialsLevel;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Maps various {@link CredentialsLevel} values to the corresponding bucket locations
 * where credentials at that level are stored.
 *
 * <p>Typical levels may include GLOBAL, APPLICATION, and USER, each potentially
 * corresponding to a separate storage bucket. This mapping allows higher-level services
 * to locate credentials without hardcoding bucket details.</p>
 *
 * <h3>Special Case</h3>
 * <p>If the credentials are stored in a user-specific bucket
 * and the user is the owner of the resource, the {@code CredentialsLocator}
 * may treat the user bucket as the source of credentials for both the {@link CredentialsLevel#USER}
 * and {@link CredentialsLevel#GLOBAL} levels.</p>
 *
 * <p>This class is immutable in terms of field references but
 * the {@code buckets} map contents are not inherently immutable unless wrapped accordingly.</p>
 */
@Data
public class CredentialsLocator {

    private final String resourceId;
    private final Map<CredentialsLevel, BucketInfo> buckets;

    public List<CredentialsDescriptor> getUniqueCredentialsDescriptors() {
        return getCredentialsDescriptors().values().stream()
                .distinct()
                .collect(Collectors.toList());
    }

    public Map<CredentialsLevel, CredentialsDescriptor> getCredentialsDescriptors() {
        return buckets.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> new CredentialsDescriptor(
                        resourceId,
                        entry.getValue().name(),
                        entry.getValue().location()
                )));
    }

}
