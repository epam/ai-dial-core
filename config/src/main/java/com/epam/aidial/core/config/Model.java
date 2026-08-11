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
    /**
     * Deprecated, no longer has any effect: the node order used to build upstream cache keys is now
     * always {@link InterfaceType#getFieldsHashingOrder()}, fixed by the API's wire format.
     * Kept only so configs that still set this field continue to parse.
     */
    @Deprecated
    @JsonAlias({"fieldsHashingOrder", "fields_hashing_order"})
    private List<String> fieldsHashingOrder = List.of("prefix.body.tools", "prefix.body.messages");
    @JsonAlias({"embeddingDimensions", "embedding_dimensions"})
    private Integer embeddingDimensions;

    public Model() {
        setMaxRetryAttempts(5);
    }
}
