package com.epam.aidial.core.server.service;

/**
 * A service that can schedule commands to run after a given delay, or to execute periodically.
 * The schedule methods create tasks with various delays and return a task object that can be used to cancel or check execution.
 */
public interface ScheduledService {

    /**
     * Submits a periodic action that becomes enabled first after the given initial delay, and subsequently with the given period;
     * that is, executions will commence after initialDelay, then initialDelay + period, then initialDelay + 2 * period, and so on.
     *
     * @param initialDelay – the time to delay first execution
     * @param delay – the period between successive executions
     * @param task - the task to execute
     * @return instance of timer
     */
    Timer scheduleWithFixedDelay(long initialDelay, long delay, Runnable task);

    interface Timer extends AutoCloseable {
    }

}
