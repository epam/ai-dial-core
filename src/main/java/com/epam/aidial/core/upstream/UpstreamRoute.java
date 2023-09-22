package com.epam.aidial.core.upstream;

import com.epam.aidial.core.config.Upstream;
import lombok.RequiredArgsConstructor;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

@RequiredArgsConstructor
public class UpstreamRoute implements Iterator<Upstream> {

    private final List<Upstream> upstreams;
    private final AtomicLong counter;
    private final int offset;

    private Upstream current;
    private int count;

    public int attempts() {
        return count;
    }

    @Override
    public boolean hasNext() {
        return count < upstreams.size();
    }

    /**
     * @return next endpoint to route to.
     */
    @Override
    public Upstream next() {
        if (hasNext()) {
            if (count > 0) {
                counter.incrementAndGet(); // advance but do not use, anyway we need to write smart thing later
            }

            int index = (offset + count++) % upstreams.size();
            current = upstreams.get(index);
            return current;
        }

        return null;
    }

    /**
     * @return current endpoint to route to.
     */
    public Upstream get() {
        Objects.requireNonNull(current);
        return current;
    }
}