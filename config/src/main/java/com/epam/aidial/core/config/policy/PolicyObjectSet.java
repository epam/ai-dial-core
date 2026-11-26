package com.epam.aidial.core.config.policy;

import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.ApiSchemaType;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a policy covers: every deployment ({@code "*"}), or a list of deployment names and package references
 * ({@code @<package>}), kept as written. {@link #expand} replaces the package references by the packages' members.
 */
@ApiSchema(
        oneOf = {String.class},
        oneOfTypes = {@ApiSchemaType(implementation = List.class, typeArguments = {String.class})}
)
@EqualsAndHashCode
@ToString
public final class PolicyObjectSet {

    public static final String ALL = "*";
    public static final String PACKAGE_PREFIX = "@";

    private static final String SHAPE_ERROR = "object must be \"*\", \"@<package>\" or a list of deployment names and package references";

    private final Set<String> terms;
    // a lone package reference written as a string, which reads back as a string
    @EqualsAndHashCode.Exclude
    private final boolean isSingleReference;

    private PolicyObjectSet(Set<String> terms, boolean isSingleReference) {
        this.terms = terms;
        this.isSingleReference = isSingleReference;
    }

    public static PolicyObjectSet all() {
        return new PolicyObjectSet(null, false);
    }

    public static PolicyObjectSet of(List<String> terms) {
        return new PolicyObjectSet(Set.copyOf(terms), false);
    }

    public boolean isAll() {
        return terms == null;
    }

    public boolean contains(String term) {
        return terms == null || terms.contains(term);
    }

    public boolean intersects(PolicyObjectSet other) {
        if (isAll() || other.isAll()) {
            return true;
        }
        return terms.stream().anyMatch(other.terms::contains);
    }

    /**
     * The names of the packages the set references, without the {@code @}.
     */
    public Set<String> packageNames() {
        if (terms == null) {
            return Set.of();
        }
        return terms.stream()
                .filter(term -> term.startsWith(PACKAGE_PREFIX))
                .map(term -> term.substring(PACKAGE_PREFIX.length()))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The set with each package reference replaced by the package's members; a reference to a package missing from
     * {@code packagesByName} adds nothing.
     */
    public PolicyObjectSet expand(Map<String, PolicyPackage> packagesByName) {
        if (terms == null) {
            return this;
        }
        Set<String> names = new HashSet<>();
        for (String term : terms) {
            if (!term.startsWith(PACKAGE_PREFIX)) {
                names.add(term);
                continue;
            }
            PolicyPackage policyPackage = packagesByName.get(term.substring(PACKAGE_PREFIX.length()));
            if (policyPackage != null && policyPackage.getDeployments() != null) {
                names.addAll(policyPackage.getDeployments());
            }
        }
        return new PolicyObjectSet(Set.copyOf(names), false);
    }

    /**
     * The position of the first name of {@code chain} the set contains, 0 for {@code "*"}, or -1 when it contains none.
     */
    public int indexOfFirstCovered(List<String> chain) {
        if (terms == null) {
            return 0;
        }
        for (int i = 0; i < chain.size(); i++) {
            if (terms.contains(chain.get(i))) {
                return i;
            }
        }
        return -1;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static PolicyObjectSet fromJson(Object value) {
        if (value instanceof String text) {
            if (ALL.equals(text)) {
                return all();
            }
            if (isPackageReference(text)) {
                return new PolicyObjectSet(Set.of(text), true);
            }
            throw new IllegalArgumentException(SHAPE_ERROR);
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                throw new IllegalArgumentException("object must not be an empty list");
            }
            for (Object element : list) {
                if (!(element instanceof String term) || term.isBlank() || ALL.equals(term)
                        || term.startsWith(PACKAGE_PREFIX) && !isPackageReference(term)) {
                    throw new IllegalArgumentException("object must list deployment names and package references; use \"*\" for all deployments");
                }
            }
            return of(list.stream().map(String.class::cast).toList());
        }
        throw new IllegalArgumentException(SHAPE_ERROR);
    }

    @JsonValue
    Object toJson() {
        if (terms == null) {
            return ALL;
        }
        return isSingleReference ? terms.iterator().next() : terms.stream().sorted().toList();
    }

    private static boolean isPackageReference(String term) {
        if (!term.startsWith(PACKAGE_PREFIX)) {
            return false;
        }
        String name = term.substring(PACKAGE_PREFIX.length());
        return !name.isBlank() && !name.contains(PACKAGE_PREFIX) && !name.contains("/");
    }
}
