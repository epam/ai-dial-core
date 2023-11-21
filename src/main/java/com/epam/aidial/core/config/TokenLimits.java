package com.epam.aidial.core.config;

import lombok.Data;

@Data
public class TokenLimits {
    private Integer maxTotalTokens;
    private Integer maxPromptTokens;
    private Integer maxCompletionTokens;
}