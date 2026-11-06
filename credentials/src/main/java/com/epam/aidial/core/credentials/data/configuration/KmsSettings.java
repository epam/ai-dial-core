package com.epam.aidial.core.credentials.data.configuration;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

import java.util.Locale;
import java.util.Set;

@Data
@Builder
@Jacksonized
public class KmsSettings {
    private static final String UNENCRYPTED_PROVIDER = "unencrypted";
    private static final Set<String> KMS_PROVIDERS = Set.of("aws", "azure", "gcp");

    private String provider;
    private String keyId;
    private String region;
    private String encryptionAlgorithm;
    private CacheSettings cache;

    /**
     * True when no provider is set or it is {@code unencrypted}: the content encryption keys are then stored in plaintext.
     */
    @JsonIgnore
    public boolean isUnencrypted() {
        return provider == null || UNENCRYPTED_PROVIDER.equals(provider);
    }

    /**
     * True only for a supported KMS provider, which wraps the content encryption keys. An unsupported provider is
     * neither this nor {@link #isUnencrypted()}: Core refuses to start with it.
     */
    @JsonIgnore
    public boolean isEnabled() {
        return provider != null && KMS_PROVIDERS.contains(provider.toLowerCase(Locale.ROOT));
    }
}
