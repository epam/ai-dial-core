package com.epam.aidial.core.server.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CopyResourcesRequest {
    String sourceUrl;
    String destinationUrl;
    boolean overwrite;

    /**
     * Indicates whether credentials associated with the source resource should be copied.
     *
     * <p>Only credentials defined at the {@code Global} level are eligible for copying.
     * Credentials at any other level are ignored.
     *
     * <p>If no global credentials are found, the copy operation proceeds without them
     * and no error is thrown.
     */
    boolean copyCredentials;
}