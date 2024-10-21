package com.epam.aidial.core.server.log;

import com.epam.aidial.core.server.ProxyContext;

public interface LogStore {

    void save(ProxyContext context);
}
