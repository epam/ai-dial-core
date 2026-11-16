package com.epam.aidial.core.server.data;

import com.fasterxml.jackson.annotation.JsonInclude;
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
    // the router whose decision chose the deployment, so a stored answer read back names it in its model
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String router;
}
