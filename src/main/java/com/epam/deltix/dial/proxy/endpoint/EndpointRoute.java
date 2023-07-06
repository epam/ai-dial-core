package com.epam.deltix.dial.proxy.endpoint;

import lombok.RequiredArgsConstructor;

import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

@RequiredArgsConstructor
public class EndpointRoute implements Iterator<String> {

    private final String[] endpoints;
    private final AtomicLong counter;
    private final int offset;
    private String current;
    private int count;

    @Override
    public boolean hasNext() {
        return count < endpoints.length;
    }

    /**
     * @return next endpoint to route to.
     */
    @Override
    public String next() {
        if (hasNext()) {
            if (count > 0) {
                counter.incrementAndGet(); // advance but do not use, anyway we need to write smart thing later
            }

            int index = (offset + count++) % endpoints.length;
            current = endpoints[index];
            return current;
        }

        return null;
    }

    /**
     * @return current endpoint to route to.
     */
    public String get() {
        Objects.requireNonNull(current);
        return current;
    }
}
