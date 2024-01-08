package com.epam.aidial.core.token;

import lombok.Data;

@Data
public class TokenUsage {
    private long completionTokens;
    private long promptTokens;
    private long totalTokens;

    public void increase(TokenUsage usage) {
        if (usage == null) {
            return;
        }
        completionTokens += usage.completionTokens;
        promptTokens += usage.promptTokens;
        totalTokens += usage.totalTokens;
    }

    @Override
    public String toString() {
        return "completion=" + completionTokens
                + ", prompt=" + promptTokens
                + ", total=" + totalTokens;
    }
}