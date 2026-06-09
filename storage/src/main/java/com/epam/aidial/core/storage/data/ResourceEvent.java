package com.epam.aidial.core.storage.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResourceEvent {

    private String url;
    private Action action;
    private long timestamp;
    private String etag;
    private String senderPodId;

    public enum Action {
        CREATE, UPDATE, DELETE
    }
}