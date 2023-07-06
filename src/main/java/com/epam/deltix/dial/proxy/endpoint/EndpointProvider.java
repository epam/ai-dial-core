package com.epam.deltix.dial.proxy.endpoint;

import java.util.Map;

public interface EndpointProvider {
    String getName();
    Map<String, String> getEndpoints();
}
