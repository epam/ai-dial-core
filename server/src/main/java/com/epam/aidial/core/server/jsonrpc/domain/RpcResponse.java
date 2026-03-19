package com.epam.aidial.core.server.jsonrpc.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ValueNode;
import lombok.Data;

import javax.annotation.Nullable;

@Data
public class RpcResponse {
    public static final String VERSION = "2.0";
    private ValueNode id;
    @Nullable
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Object result;
    @Nullable
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private ErrorMessage error;
    private String jsonrpc;

    public RpcResponse() {
    }

    public RpcResponse(@Nullable ErrorMessage error) {
        jsonrpc = VERSION;
        id = NullNode.getInstance();
        this.error = error;
    }

    @JsonIgnore
    public RpcResponse copyWith(ValueNode id) {
        RpcResponse response = new RpcResponse();
        response.jsonrpc = this.jsonrpc;
        response.error = this.error;
        response.result = this.result;
        response.id = id;
        return response;
    }
}