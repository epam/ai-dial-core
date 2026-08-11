package com.epam.aidial.core.server.token;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PromptTokensDetails {
    @JsonProperty("cached_tokens")
    @JsonAlias({"cachedTokens"})
    private long cachedTokens;
    @JsonProperty("cache_write_tokens")
    @JsonAlias({"cacheWriteTokens"})
    private long cacheWriteTokens;

    public void increase(PromptTokensDetails other) {
        if (other == null) {
            return;
        }
        cachedTokens += other.cachedTokens;
        cacheWriteTokens += other.cacheWriteTokens;
    }
}
