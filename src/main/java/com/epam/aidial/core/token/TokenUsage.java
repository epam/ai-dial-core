package com.epam.aidial.core.token;

import lombok.Data;

@Data
public class TokenUsage {
    private long completionTokens;
    private long promptTokens;
    private long totalTokens;

    @Override
    public String toString() {
        return "completion=" + completionTokens
                + ", prompt=" + promptTokens
                + ", total=" + totalTokens;
    }
}