package com.epam.aidial.core.server.limiter;

/**
 * The window that decided a rejection: the fired one with the longest wait, the longer window on a tie.
 */
public record LimitBreach(Kind kind, Window window, long retryAfterSeconds) {

    public enum Kind {
        TOKENS, REQUESTS, COST
    }

    public enum Window {
        MINUTE("per-minute", true),
        HOUR("hourly", true),
        DAY("daily", false),
        WEEK("weekly", false),
        MONTH("monthly", false);

        private final String label;
        private final boolean rolling;

        Window(String label, boolean rolling) {
            this.label = label;
            this.rolling = rolling;
        }

        public String label() {
            return label;
        }

        public boolean isRolling() {
            return rolling;
        }
    }

    /**
     * Keeps the fired window with the largest retry-after while windows are offered shortest first.
     */
    static final class Picker {
        private final Kind kind;
        private Window window;
        private long retryAfterSeconds = -1;

        Picker(Kind kind) {
            this.kind = kind;
        }

        void offer(Window candidate, long candidateRetryAfterSeconds) {
            if (candidateRetryAfterSeconds >= retryAfterSeconds) {
                window = candidate;
                retryAfterSeconds = candidateRetryAfterSeconds;
            }
        }

        LimitBreach build() {
            return new LimitBreach(kind, window, retryAfterSeconds);
        }
    }
}
