package com.epam.aidial.core.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResourceEvent {

    private String url;
    private Action action;
    private long timestamp;
    private String etag;

    public enum Action {
        CREATE, UPDATE, DELETE
    }
}