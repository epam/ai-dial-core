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
    /**
     * Informational: the hosting provider that supplies the model's API endpoint, e.g. {@code AWS}, {@code Google}
     * or {@code Azure}. Free-form; not interpreted by DIAL Core.
     */
    private String provider;
    /**
     * Informational: ISO-8601 date ({@code yyyy-MM-dd}) after which the model is considered deprecated, even though
     * existing deployments keep working. Not interpreted by DIAL Core.
     */
    @JsonAlias({"deprecationDate", "deprecation_date"})
    private String deprecationDate;
    /**
     * Informational: ISO-8601 date ({@code yyyy-MM-dd}) when the provider terminates access to the model, making it
     * unavailable. Not interpreted by DIAL Core.
     */
    @JsonAlias({"endOfService", "end_of_service"})
    private String endOfService;

    public Model() {
        setMaxRetryAttempts(5);
    }
}
