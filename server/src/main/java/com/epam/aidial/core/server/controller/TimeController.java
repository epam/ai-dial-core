package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@RequiredArgsConstructor
public class TimeController {

    private final ProxyContext context;

    public Future<?> getCurrentTime() {
        String currentTime = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        return context.respond(HttpStatus.OK, new TimeResponse(currentTime));
    }

    private static class TimeResponse {
        private final String currentTime;

        public TimeResponse(String currentTime) {
            this.currentTime = currentTime;
        }

        public String getCurrentTime() {
            return currentTime;
        }
    }
}
