package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Set;

@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
public class Model extends Deployment {
    private ModelType type;
    private String tokenizerModel;
    private TokenLimits limits;
    private Pricing pricing;
    private List<Upstream> upstreams = List.of();
    // if it's set then the model name is overridden with that name in the request body to the model adapter
    private String overrideName;
    @JsonAlias({"fieldsHashingOrder", "fields_hashing_order"})
    private List<String> fieldsHashingOrder = List.of("prefix.body.tools", "prefix.body.messages");
    @JsonAlias({"embeddingDimensions", "embedding_dimensions"})
    private Integer embeddingDimensions;

    private static final Set<String> SUPPORTED_INTERFACE_KEYS = Set.of(
            InterfaceType.OPENAI_CHAT_COMPLETIONS.getValue(),
            InterfaceType.OPENAI_RESPONSES.getValue()
    );

    public Model() {
        setMaxRetryAttempts(5);
    }

    @Override
    public Set<String> supportedInterfaceKeys() {
        return SUPPORTED_INTERFACE_KEYS;
    }
}