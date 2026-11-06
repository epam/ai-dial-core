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

    /** The access types this dependency requests: READ and/or WRITE. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Set<ResourceAccessType> access = Set.of();

    /**
     * When {@code true}, an unresolvable or unconsented dependency fails the call with an explicit
     * 403 instead of degrading silently. Use {@code true} for dependencies the app cannot function
     * without; {@code false} (default) for optional enhancements.
     */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean required;

    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Target {

        /**
         * A DIAL resource URL in one of two forms:
         * <ul>
         *   <li>concrete global-view path: {@code files/public/policies/}</li>
         *   <li>current-user placeholder path: {@code files/{current-user}/skills/}</li>
         * </ul>
         * Write-time validation enforces the two-form grammar; the resolver enforces it again at
         * request start for config-file apps that bypass write-time validation.
         */
        private String path;
    }
}
