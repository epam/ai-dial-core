package com.epam.aidial.core.upstream;

import com.epam.aidial.core.config.Upstream;
import lombok.RequiredArgsConstructor;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;

@RequiredArgsConstructor
public class UpstreamRoute implements Iterator<Upstream> {

    private final List<Upstream> upstreams;
    private final int offset;
    /**
     * The maximu number of retries for all upstreams.
     */
    private final int maxRetries;

    private Upstream upstream;
    private int retries;
    private int next;
    private int prev;

    /**
     * @return the number of used upstreams.
     */
    public int attempts() {
        return next;
    }

    /**
     * @return the total number of retries due to connection errors.
     */
    public int retries() {
        return retries;
    }

    @Override
    public boolean hasNext() {
        return next < upstreams.size() && retries < maxRetries;
    }

    /**
     * @return next endpoint to route to.
     */
    @Override
    public Upstream next() {
        if (hasNext()) {
            prev = next++;
            int index = (offset + prev) % upstreams.size();
            upstream = upstreams.get(index);
            return upstream;
        }

        return null;
    }

    /**
     * @return current endpoint to route to.
     */
    public Upstream get() {
        Objects.requireNonNull(upstream);
        return upstream;
    }

    /**
     * Retry the current endpoint because some error happened while sending a request.
     */
    public void retry() {
        if (retries < maxRetries && prev < next) {
            retries++;
            next = prev;
        }
    }
}