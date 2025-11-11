package com.epam.aidial.core.server.data.permission;

import lombok.Data;

@Data
public class ListPermissionRequest {
    /**
     * Shared resource direction. Can be either with - me or others.
     */
    ShareWith with;

    public enum ShareWith {
        ME, OTHERS
    }
}
