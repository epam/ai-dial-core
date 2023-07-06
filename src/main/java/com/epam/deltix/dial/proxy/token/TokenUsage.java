package com.epam.deltix.dial.proxy.token;

import lombok.Data;

@Data
public class TokenUsage {
    private long completionTokens;
    private long promptTokens;
    private long totalTokens;

    @Override
    public String toString() {
        return "completion=" + completionTokens +
                ", prompt=" + promptTokens +
                ", total=" + totalTokens;
    }
}