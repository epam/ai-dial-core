package com.epam.aidial.core.credentials.data.configuration;

import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

@Data
@Builder
@Jacksonized
public class SecuritySettings {
    private KmsSettings kms;
}
