package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Router;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * A router's verdicts for one request and how far Core got through their candidates. One request walks the
 * candidates in order, from one stage of its future chain to the next, so no two threads touch it at once. A new
 * verdict, asked for when every candidate so far failed, appends its candidates to the same list.
 */
@Getter
public class RouterDecision {

    private final String router;
    private final InterfaceType api;
    private final List<String> candidates;
    private final List<Attempt> attempts = new ArrayList<>();
    private long decideMs;
    private int decisions = 1;
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
    /**
     * True once a new verdict could not be had: the walk then answers the last failure instead of deciding again.
     */
    private boolean exhausted;
    // what a new decide request is built from; not part of the analytics row
    @Setter
    private Router routerConfig;
    @Setter
    private DeploymentInterface entry;
    @Setter
    private ApiKeyData keyTemplate;
    // where the router row sits in the call tree, fixed when the router answered
    @Setter
    private Hop hop;
    // the router row is written, or left to the poller of the background job this request started
    private boolean rowTaken;

    public RouterDecision(String router, InterfaceType api, List<String> candidates, long decideMs) {
        this.router = router;
        this.api = api;
        this.candidates = new ArrayList<>(candidates);
        this.decideMs = decideMs;
    }

    public String current() {
        return candidates.get(index);
    }

    public boolean hasNext() {
        return !closed && index + 1 < candidates.size();
    }

    public boolean canRedecide(int maxDecisions) {
        return !closed && !exhausted && decisions < maxDecisions;
    }

    /**
     * Records why the current candidate was skipped and moves to the next one, which is resolved anew.
     */
    public void skip(int status, String reason) {
        skip(status, reason, false);
    }

    public void skip(int status, String reason, boolean received) {
        fail(status, reason, received);
        index++;
        resolving = true;
    }

    /**
     * Records why the current candidate failed without moving: the next candidate comes from a new verdict.
     */
    public void fail(int status, String reason, boolean received) {
        attempts.add(new Attempt(current(), status, reason, received, false));
    }

    /**
     * Appends a new verdict's candidates and moves to the first of them, which is resolved anew.
     */
    public void redecided(List<String> next, long ms) {
        index = candidates.size();
        candidates.addAll(next);
        decideMs += ms;
        decisions++;
        resolving = true;
    }

    public void exhaust() {
        exhausted = true;
    }

    public void close() {
        closed = true;
    }

    /**
     * True the first time only: the router row is written once, whichever terminal path asks for it.
     */
    public boolean takeRow() {
        if (rowTaken) {
            return false;
        }
        rowTaken = true;
        return true;
    }

    public void releaseRow() {
        rowTaken = false;
    }

    public Row toRow() {
        return new Row(router, api.getValue(), List.copyOf(candidates), current(), decideMs, List.copyOf(attempts),
                decisions > 1 ? decisions : null);
    }

    /**
     * The {@code decision} object of the analytics row. {@code decisions} only when the request needed more than one.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Row(String router, String api, List<String> candidates, String deployment, long decideMs, List<Attempt> attempts,
                      @JsonInclude(JsonInclude.Include.NON_NULL) Integer decisions) {
    }

    /**
     * The router row's own span, only in the analytics rows: the request's parent span is its parent, and the row of
     * the pass that asked the router is its child. {@code parentDeployment} and {@code executionPath} are those of a
     * direct call to the router.
     */
    public record Hop(String spanId, String parentSpanId, String parentDeployment, List<String> executionPath) {
    }

    /**
     * One candidate tried. {@code received}: its upstream had answered; {@code partial}: bytes of that answer had
     * reached the client. Both are left out when false.
     */
    public record Attempt(String deployment, int status, String reason,
                          @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean received,
                          @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean partial) {

        public Attempt(String deployment, int status, String reason) {
            this(deployment, status, reason, false, false);
        }
    }
}
