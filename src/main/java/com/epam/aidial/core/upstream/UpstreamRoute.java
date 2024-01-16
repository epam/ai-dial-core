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
    private final int maxAttempts;

    private Upstream upstream;
    private int attempts;
    private int next;
    private int prev;

    public int attempts() {
        return attempts;
    }

    @Override
    public boolean hasNext() {
        return next < upstreams.size() && attempts < maxAttempts;
    }

    /**
     * @return next endpoint to route to.
     */
    @Override
    public Upstream next() {
        if (hasNext()) {
            attempts++;
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

    public void retry() {
        if (next > prev) {
            next = prev;
        }
    }
}