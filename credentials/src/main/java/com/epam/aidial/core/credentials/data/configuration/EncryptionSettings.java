package com.epam.aidial.core.credentials.data.configuration;

import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

@Data
@Builder
@Jacksonized
public class EncryptionSettings {

    @Builder.Default
    private String algorithm = "AES";
    @Builder.Default
    private int keySize = 256;
    @Builder.Default
    private String cipherTransformation = "AES/GCM/NoPadding";
    @Builder.Default
    private int ivLengthBytes = 12;
    @Builder.Default
    private int gcmTagLengthBits = 128;

}
