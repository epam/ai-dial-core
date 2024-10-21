package com.epam.aidial.core.server.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class MoveResourcesRequest {
    String sourceUrl;
    String destinationUrl;
    boolean overwrite;
}
