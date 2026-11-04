package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.InterfaceType;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * A router's verdict for one request and how far Core got through its candidates. One request walks the
 * candidates in order, from one stage of its future chain to the next, so no two threads touch it at once.
 */
@Getter
public class RouterDecision {

    private final String router;
    private final InterfaceType api;
    private final List<String> candidates;
    private final long decideMs;
    private final List<Attempt> attempts = new ArrayList<>();
    private int index;
    /**
     * True while the current candidate is being resolved and limit-checked: a refusal then skips to the next
     * candidate. Cleared once it passed, so a body or parse failure after that answers the client.
     */
    @Setter
    private boolean resolving = true;
    /**
     * True once the request was handed to the chosen deployment's interceptors: the deployment is then
     * called from another pass, which this one cannot fall back from.
     */
    private boolean closed;

    public RouterDecision(String router, InterfaceType api, List<String> candidates, long decideMs) {
        this.router = router;
        this.api = api;
        this.candidates = List.copyOf(candidates);
        this.decideMs = decideMs;
    }

    public String current() {
        return candidates.get(index);
    }

    public boolean hasNext() {
        return !closed && index + 1 < candidates.size();
    }

    /**
     * Records why the current candidate was skipped and moves to the next one, which is resolved anew.
     */
    public void skip(int status, String reason) {
        attempts.add(new Attempt(current(), status, reason));
        index++;
        resolving = true;
    }

    public void close() {
        closed = true;
    }

    public Row toRow() {
        return new Row(router, api.getValue(), candidates, current(), decideMs, List.copyOf(attempts));
    }

    /**
     * The {@code decision} object of the analytics row.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Row(String router, String api, List<String> candidates, String deployment, long decideMs, List<Attempt> attempts) {
    }

    public record Attempt(String deployment, int status, String reason) {
    }
}
