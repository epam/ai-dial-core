package com.epam.aidial.core.server.data.clientchannel.topic;

import com.epam.aidial.core.server.jsonrpc.domain.RpcResponse;
import lombok.Data;

@Data
public class PubSubResponse {
    private String channelId;
    private RpcResponse response;

    public PubSubResponse() {
    }

    public PubSubResponse(String channelId, RpcResponse response) {
        this.channelId = channelId;
        this.response = response;
    }
}
