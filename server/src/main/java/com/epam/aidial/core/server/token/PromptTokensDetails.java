package com.epam.aidial.core.server.token;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PromptTokensDetails {
    @JsonAlias({"cached_tokens", "cachedTokens"})
    private long cachedTokens;
    @JsonAlias({"cache_write_tokens", "cacheWriteTokens"})
    private long cacheWriteTokens;

    public void increase(PromptTokensDetails other) {
        if (other == null) {
            return;
        }
        cachedTokens += other.cachedTokens;
        cacheWriteTokens += other.cacheWriteTokens;
    }
}
