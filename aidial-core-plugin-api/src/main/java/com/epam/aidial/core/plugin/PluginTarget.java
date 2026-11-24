package com.epam.aidial.core.plugin;

/**
 * A place in the config where a plugin url {@code dial-plugin://<name>} may name an entry of a provider: a hint for the
 * admin console, which offers a provider's entries there. Core does not check a plugin url against it.
 */
public enum PluginTarget {
    /**
     * The base url of a model or of one of its interfaces.
     */
    MODEL,
    /**
     * The base url of an application or of one of its interfaces.
     */
    APPLICATION,
    /**
     * The base url of a router or of one of its interfaces.
     */
    ROUTER,
    /**
     * The base url of a translator.
     */
    TRANSLATOR
}
