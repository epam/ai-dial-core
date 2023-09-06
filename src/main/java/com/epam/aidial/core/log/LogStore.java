package com.epam.aidial.core.log;

import com.epam.aidial.core.ProxyContext;

public interface LogStore {

    void save(ProxyContext context);
}
