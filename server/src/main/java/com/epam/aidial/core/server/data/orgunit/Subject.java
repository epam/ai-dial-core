package com.epam.aidial.core.server.data.orgunit;

import com.epam.aidial.core.server.data.Rule;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import javax.annotation.Nullable;

/**
 * Who a role assignment is for, in the DIAL 2.0 design's format: {@code {"type":"user","id":…}},
 * {@code {"type":"apiKey","id":…}}, {@code {"type":"claimsPredicate","source":…,"function":…,"targets":[…]}}
 * and {@code {"type":"any"}}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Subject.User.class, name = "user"),
    @JsonSubTypes.Type(value = Subject.ApiKey.class, name = "apiKey"),
    @JsonSubTypes.Type(value = Subject.ClaimsPredicate.class, name = "claimsPredicate"),
    @JsonSubTypes.Type(value = Subject.Any.class, name = "any")
})
public sealed interface Subject {

    record User(String id) implements Subject {

        public User {
            requireId(id);
        }
    }

    record ApiKey(String id) implements Subject {

        public ApiKey {
            requireId(id);
        }
    }

    /**
     * A read rule ({@link Rule}) on the caller's token claims, inside the typed subject envelope.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ClaimsPredicate(String source, Rule.Function function, @Nullable List<String> targets) implements Subject {

        public ClaimsPredicate {
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException("A claims predicate needs a source");
            }

            if (function == null) {
                throw new IllegalArgumentException("A claims predicate needs a function");
            }

            targets = targets == null ? null : List.copyOf(targets);
        }
    }

    record Any() implements Subject {
    }

    private static void requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A subject id must not be blank");
        }
    }
}
