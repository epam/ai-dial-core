package com.epam.aidial.core.config;

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
}