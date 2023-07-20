package com.epam.deltix.dial.proxy.upstream;

import com.epam.deltix.dial.proxy.config.Upstream;

import java.util.List;

public interface UpstreamProvider {
    String getName();

    List<Upstream> getUpstreams();
}
