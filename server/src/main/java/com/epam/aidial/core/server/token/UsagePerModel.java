package com.epam.aidial.core.server.token;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single entry of {@code statistics.usage_per_model}: the token usage aggregated for one
 * reporting deployment across the call tree. See {@link TokenStatsTracker} for how entries are
 * accumulated and {@link Usage} for why the nested breakdown isn't just a {@link TokenUsage}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UsagePerModel {
    private Integer index;
    private String model;
    private Usage usage;

    public UsagePerModel(String model, TokenUsage tokenUsage) {
        this(null, model, new Usage(tokenUsage));
    }
}
