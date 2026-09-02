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
     * Whether a request served this way is one the caller is accountable for — the only kind the limits
     * are read or written for at all. False for a mode that has a second request serve the completion:
     * that inner request is a real one, and it carries the tokens, the cost and the request slot, so
     * doing anything here would count one client call twice. A caller who has exhausted a quota is still
     * rejected, on that inner request rather than on this one.
     *
     * <p>Declared per mode rather than tested for at each call site, so that a mode added later has to
     * answer the question once, here, instead of wherever limits happen to be read.
     */
    private final boolean subjectToLimits;

    InterfaceMode(String value, boolean subjectToLimits) {
        this.value = value;
        this.subjectToLimits = subjectToLimits;
    }
}
