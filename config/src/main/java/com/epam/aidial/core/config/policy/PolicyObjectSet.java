package com.epam.aidial.core.config.policy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.List;
import java.util.Set;

/**
 * What a policy covers: every deployment ({@code "*"}) or an explicit list of deployments.
 */
@EqualsAndHashCode
@ToString
public final class PolicyObjectSet {

    public static final String ALL = "*";

    private final Set<String> deployments;

    private PolicyObjectSet(Set<String> deployments) {
        this.deployments = deployments;
    }

    public static PolicyObjectSet all() {
        return new PolicyObjectSet(null);
    }

    public static PolicyObjectSet of(List<String> deployments) {
        return new PolicyObjectSet(Set.copyOf(deployments));
    }

    public boolean isAll() {
        return deployments == null;
    }

    public boolean contains(String deployment) {
        return deployments == null || deployments.contains(deployment);
    }

    public boolean intersects(PolicyObjectSet other) {
        if (isAll() || other.isAll()) {
            return true;
        }
        return deployments.stream().anyMatch(other.deployments::contains);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static PolicyObjectSet fromJson(Object value) {
        if (value instanceof String text) {
            if (!ALL.equals(text)) {
                throw new IllegalArgumentException("object must be \"*\" or a list of deployment names");
            }
            return all();
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                throw new IllegalArgumentException("object must not be an empty list");
            }
            for (Object element : list) {
                if (!(element instanceof String name) || name.isBlank() || ALL.equals(name)) {
                    throw new IllegalArgumentException("object must list deployment names; use \"*\" for all deployments");
                }
            }
            return of(list.stream().map(String.class::cast).toList());
        }
        throw new IllegalArgumentException("object must be \"*\" or a list of deployment names");
    }

    @JsonValue
    Object toJson() {
        return deployments == null ? ALL : deployments.stream().sorted().toList();
    }
}
