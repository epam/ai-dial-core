package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * The {@code target} of an {@link Alias}: the deployment it forwards calls to.
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AliasTarget {

    // "<models|applications|toolsets>/<name>" for a config deployment, "<applications|toolsets>/<bucket>/<path>" for a resource
    private String path;
}
