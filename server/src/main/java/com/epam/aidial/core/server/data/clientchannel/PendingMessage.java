package com.epam.aidial.core.server.data.clientchannel;

import com.epam.aidial.core.server.jsonrpc.domain.RpcRequest;
import com.epam.aidial.core.server.jsonrpc.domain.RpcResponse;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PendingMessage {

    private Status status;

    private RpcRequest rpcRequest;

    private RpcResponse rpcResponse;

    private long receivedAt;

    public enum Status {
        CREATED, SENT, RECEIVED
    }
}
