package com.epam.aidial.core.upstream;

import com.epam.aidial.core.config.Upstream;

import java.util.List;

public interface UpstreamProvider {
    String getName();

    List<Upstream> getUpstreams();
}
