package com.epam.aidial.core.server.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class AnthropicUsageData {

    @JsonProperty("extra_usage")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private ExtraUsage extraUsage;

    @JsonProperty("limits")
    private List<UsageLimit> limits;

    @Data
    public static class ExtraUsage {
        @JsonProperty("is_enabled")
        private boolean enabled;

        @JsonProperty("monthly_limit")
        private long monthlyLimit;

        @JsonProperty("used_credits")
        private long usedCredits;

        @JsonProperty("utilization")
        private long utilization;

        @JsonProperty("currency")
        private String currency;
    }

    @Data
    public static class UsageLimit {
        @JsonProperty("kind")
        private String kind;

        @JsonProperty("group")
        private String group;

        @JsonProperty("percent")
        private long percent;

        @JsonProperty("resets_at")
        private String resetsAt;

        @JsonProperty("severity")
        private String severity;

        @JsonProperty("is_active")
        private boolean active;
    }
}
