package com.epam.aidial.core.server.limiter.budget;

import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.SpendingLimits;

import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * The cost object a caller's calls are charged to, and where it came from.
 */
public record CostObject(String id, Source source) {

    /**
     * The cost object the root request's budget headers named, else {@link #resolve(String, Key, Supplier)}.
     */
    public static CostObject resolve(@Nullable String header, @Nullable String claim, @Nullable Key key, Supplier<SpendingLimits> settings) {
        return header != null ? new CostObject(header, Source.HEADER) : resolve(claim, key, settings);
    }

    /**
     * The token's cost object claim, else the API key's project, else {@code defaultCostObject}, which is read only
     * when neither is there.
     */
    public static CostObject resolve(@Nullable String claim, @Nullable Key key, Supplier<SpendingLimits> settings) {
        if (claim != null) {
            return new CostObject(claim, Source.CLAIM);
        }
        if (key != null && key.getProject() != null) {
            return new CostObject(key.getProject(), Source.KEY);
        }
        return new CostObject(settings.get().getDefaultCostObject(), Source.DEFAULT);
    }

    public enum Source {
        CLAIM("claim"),
        KEY("key"),
        // named by the root request's X-DIAL-COST-OBJECT or X-DIAL-BUDGET, where the identity provider has no costObjectPath
        HEADER("header"),
        DEFAULT("default");

        private final String value;

        Source(String value) {
            this.value = value;
        }

        public String toValue() {
            return value;
        }
    }
}
