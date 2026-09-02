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
     * The translator calls Core back with the converted request, so the completion is served — and its
     * usage charged to the caller's limits — by that inner request rather than this one.
     */
    TRANSLATOR("translator", false);

    @JsonValue
    private final String value;

    /**
     * Whether a request served this way is the one the caller's limits are charged for. False for a mode
     * that has a second request serve the completion: that inner request carries both the usage and the
     * request slot, so charging here as well would count one client call twice. It says nothing about
     * checking — every limit is checked before a request is forwarded, whatever mode serves it.
     *
     * <p>Declared per mode rather than tested for at each call site, so that a mode added later has to
     * answer the question once, here, instead of wherever limits happen to be read.
     */
    private final boolean chargedToInitiator;

    InterfaceMode(String value, boolean chargedToInitiator) {
        this.value = value;
        this.chargedToInitiator = chargedToInitiator;
    }
}
