package com.epam.aidial.core.service;

public interface ScheduledService {
    ScheduledTimer scheduleWithFixedDelay(long initialDelay, long delay, Runnable task);

}
