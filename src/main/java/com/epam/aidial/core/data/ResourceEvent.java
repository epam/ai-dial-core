package com.epam.aidial.core.data;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ResourceEvent {

    private String url;
    private Action action;
    private long timestamp;

    public enum Action {
        CREATE, UPDATE, DELETE
    }
}