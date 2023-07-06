package com.epam.deltix.dial.proxy.log;

import com.epam.deltix.dial.proxy.ProxyContext;

public interface LogStore {

    void save(ProxyContext context);
}
