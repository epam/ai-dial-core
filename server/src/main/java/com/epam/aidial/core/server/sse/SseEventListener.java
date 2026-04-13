package com.epam.aidial.core.server.sse;

public interface SseEventListener {
    void onEvent(SseEvent event);

    void onComment(String comment);

    void onComplete();
}
