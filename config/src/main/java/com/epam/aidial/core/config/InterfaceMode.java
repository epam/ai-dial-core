package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * How a deployment serves one {@link InterfaceType}: the request is forwarded in the shape it arrived in,
 * or handed to a translator that converts it to an API the deployment does speak.
 */
@Getter
public enum InterfaceMode {

    PASSTHROUGH("passthrough"),
    /**
     * The translator calls Core back with the converted request, so the completion is served — and its
     * tokens counted towards limits — by that inner request rather than this one.
     */
    TRANSLATOR("translator");

    @JsonValue
    private final String value;

    InterfaceMode(String value) {
        this.value = value;
    }
}
