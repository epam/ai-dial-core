package com.epam.aidial.core.server.token;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CompletionTokensDetails {
    @JsonAlias({"reasoning_tokens", "reasoningTokens"})
    private long reasoningTokens;

    public void increase(CompletionTokensDetails other) {
        if (other == null) {
            return;
        }
        reasoningTokens += other.reasoningTokens;
    }
}
