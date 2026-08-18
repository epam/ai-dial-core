package com.epam.aidial.core.storage.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Map;

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
    /** Optional per-event metadata carried from the writer pod to all replicas. */
    private Map<String, String> metadata;

    public enum Action {
        CREATE, UPDATE, DELETE
    }
}