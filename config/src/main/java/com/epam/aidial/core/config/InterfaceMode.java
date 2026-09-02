package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * How a deployment serves one {@link InterfaceType}: the request is forwarded in the shape it arrived in,
 * or handed to a translator that converts it to an API the deployment does speak.
 */
@Getter
public enum InterfaceMode {

    PASSTHROUGH("passthrough", true),
    /**
     * The translator calls Core back with the converted request, so the completion is served — and the
     * caller's limits both checked and charged — by that inner request rather than this one.
     */
    TRANSLATOR("translator", false);

    @JsonValue
    private final String value;

    /**
     * Whether the caller is accountable for a request served this way — the only kind the limits are read
     * or written for. False for a mode where a second request serves the completion and carries the
     * tokens, the cost and the request slot; a caller over quota is rejected on that one instead.
     */
    private final boolean subjectToLimits;

    InterfaceMode(String value, boolean subjectToLimits) {
        this.value = value;
        this.subjectToLimits = subjectToLimits;
    }
}
