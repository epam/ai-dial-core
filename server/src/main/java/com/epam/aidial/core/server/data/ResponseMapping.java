package com.epam.aidial.core.server.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResponseMapping {
    String upstreamResponseId;
    String upstreamKey;
    String deploymentName;
    String initiatorBucket;
}
