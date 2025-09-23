package com.epam.aidial.core.credentials.data.configuration;

import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

@Data
@Builder
@Jacksonized
public class KmsSettings {
    private String provider;
    private String keyId;
    private String region;
    private String encryptionAlgorithm;
    private CacheSettings cache;
}
