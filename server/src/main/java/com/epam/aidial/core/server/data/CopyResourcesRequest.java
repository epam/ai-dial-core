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
    boolean copyCredentials;
}