package com.epam.aidial.core.server.data;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ResponseMapping {
    String upstreamResponseId;
    String upstreamKey;
    String deploymentName;
}
