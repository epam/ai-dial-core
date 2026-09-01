package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A service converting a request from one LLM API to another, letting a deployment serve an interface it
 * does not speak itself. Registered under {@link Config#getTranslators()}, or written inline in the
 * {@code interfaces} entry that uses it — inline, {@link #in} is implied by that entry's own type.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Translator {

    /**
     * The interface type the translator accepts, as {@link InterfaceType#getValue()}. Types this Core does
     * not know are kept as written, the same way an {@code interfaces} key is.
     */
    private String in;

    /**
     * The interface type it converts to, which the deployment has to serve itself: the translator calls
     * Core back on that interface to have the completion served.
     */
    private String out;

    /**
     * Root url the ingress path is appended to at request time, exactly as a deployment's own base url is.
     */
    @JsonAlias({"baseUrl", "base_url"})
    private String baseUrl;
}
