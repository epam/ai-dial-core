package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
public class Model extends Deployment {
    private ModelType type;
    private String tokenizerModel;
    private TokenLimits limits;
    private Pricing pricing;
    private List<Upstream> upstreams = List.of();
    @JsonAlias({"fieldsHashingOrder", "fields_hashing_order"})
    private List<String> fieldsHashingOrder = List.of("prefix.body.tools", "prefix.body.messages");
    @JsonAlias({"embeddingDimensions", "embedding_dimensions"})
    private Integer embeddingDimensions;

    public Model() {
        setMaxRetryAttempts(5);
    }

    /**
     * Resolves the node order used to build upstream cache keys. {@code fieldsHashingOrder} is a
     * chat-completions-only setting (its non-null default would otherwise silently apply to interfaces
     * it was never designed for, e.g. dropping {@code system} from Anthropic hashes); the newer
     * interfaces always use their wire format's built-in order.
     */
    public List<String> resolveFieldsHashingOrder(InterfaceType interfaceType) {
        return interfaceType == InterfaceType.OPENAI_CHAT_COMPLETIONS
                ? fieldsHashingOrder
                : interfaceType.getDefaultFieldsHashingOrder();
    }
}
