package com.epam.aidial.core.server.log;

public interface LogStore {

    void save(LogContext logContext);
}
