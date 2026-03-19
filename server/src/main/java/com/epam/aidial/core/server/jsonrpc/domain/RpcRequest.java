package com.epam.aidial.core.server.jsonrpc.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.databind.node.ValueNode;
import lombok.Data;

import javax.annotation.Nullable;

@Data
public class RpcRequest {

    public static final String VERSION = "2.0";

    private String jsonrpc;
    private String method;
    private JsonNode params;
    private ValueNode id;

    public ValueNode getId() {
        return id != null ? id : NullNode.getInstance();
    }

    public JsonNode getParams() {
        return params != null ? params : NullNode.getInstance();
    }

    @JsonIgnore
    @Nullable
    public ErrorMessage validate() {
        if (VERSION.equals(jsonrpc)) {
            return new ErrorMessage(-32600, "JSON-RPC version 2.0 is only supported", null);
        }
        if (method == null || method.isEmpty()) {
            return new ErrorMessage(-32600, "method is missed", null);
        }
        return null;
    }

    @JsonIgnore
    public RpcRequest copyWith(String id) {
        RpcRequest request = new RpcRequest();
        request.method = this.method;
        request.jsonrpc = this.jsonrpc;
        request.params = this.params;
        request.id = new TextNode(id);
        return request;
    }

}