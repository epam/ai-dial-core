package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * A service converting a request from one LLM API to another, letting a deployment serve an interface it
 * does not speak itself. Registered under {@link Config#getTranslators()}, or written inline in the
 * {@code interfaces} entry that uses it.
 *
 * <p>{@link #out} and {@link #baseUrl} are what make it a translator at all — one missing either converts
 * nothing, or converts it nowhere — so it cannot be built without them.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Translator {

    /**
     * The interface type the translator accepts, as {@link InterfaceType#getValue()}. Absent only inline,
     * where the entry the definition sits under is what it converts from; a registry entry is declared under
     * no interface, so it has to say. Types this Core does not know are kept as written, the same way an
     * {@code interfaces} key is.
     */
    private final String in;

    /**
     * The interface type it converts to, which the deployment has to serve itself: the translator calls
     * Core back on that interface to have the completion served.
     */
    private final String out;

    /**
     * Root url the ingress path is appended to at request time, exactly as a deployment's own base url is.
     */
    private final String baseUrl;

    @JsonCreator
    public Translator(
            @JsonProperty("in") String in,
            @JsonProperty(value = "out", required = true) String out,
            @JsonProperty(value = "baseUrl", required = true) @JsonAlias({"baseUrl", "base_url"}) String baseUrl
    ) {
        if (out == null || out.isEmpty()) {
            throw new IllegalArgumentException("Translator out cannot be null or empty");
        }
        if (baseUrl == null || baseUrl.isEmpty()) {
            throw new IllegalArgumentException("Translator baseUrl cannot be null or empty");
        }
        this.in = in;
        this.out = out;
        this.baseUrl = baseUrl;
    }
}
