package com.epam.aidial.core.server.data.clientchannel.topic;

import com.epam.aidial.core.server.jsonrpc.domain.RpcRequest;
import lombok.Data;

@Data
public class PubSubRequest {
    private String channelId;
    private RpcRequest request;

    public PubSubRequest() {
    }

    public PubSubRequest(String channelId, RpcRequest request) {
        this.channelId = channelId;
        this.request = request;
    }
}
