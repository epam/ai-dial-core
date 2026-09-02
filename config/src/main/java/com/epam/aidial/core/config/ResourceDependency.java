package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Set;

/**
 * A declared resource dependency: a request, not a grant. The record carries no access of its
 * own — at request start Core verifies the originating user's reach and the required consent,
 * and only then bakes the grant into the per-request key the app already holds.
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ResourceDependency {

    /** The single descriptor kind for every DIAL target; the target type is clear from the path. */
    public static final String KIND = "dial.resourceLink";

    private String kind;

    @JsonAlias({"linkId", "link_id"})
    private String linkId;

    private Target target;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Set<ResourceAccessType> access = Set.of();

    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean required;

    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Target {

        // Two forms only, nothing else: a concrete global-view path, or a current-user placeholder rooted path.
        private String path;
    }
}
