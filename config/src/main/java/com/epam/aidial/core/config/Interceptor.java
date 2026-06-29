package com.epam.aidial.core.config;

import java.util.Set;

public class Interceptor extends Deployment {

    /**
     * Interceptors operate only on the OpenAI chat completions interface; the Responses API
     * (and other interfaces) are not supported through a config-declared {@code interfaces} entry.
     */
    @Override
    public Set<String> supportedInterfaceKeys() {
        return Set.of(InterfaceType.OPENAI_CHAT_COMPLETIONS.getValue());
    }
}
