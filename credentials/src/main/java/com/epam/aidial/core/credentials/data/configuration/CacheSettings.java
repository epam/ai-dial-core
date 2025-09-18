package com.epam.aidial.core.credentials.data.configuration;

import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

@Data
@Builder
@Jacksonized
public class CacheSettings {
    @Builder.Default
    private boolean enabled = true;
    @Builder.Default
    private long maxSize = 10_000L;
    @Builder.Default
    private long expiration = 600_000L;
}
