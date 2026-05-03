package com.epam.aidial.core.server.config;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Value;

import java.util.List;
import javax.annotation.Nullable;

/**
 * In-memory derived state — one entry in {@link MergedConfigStore}'s
 * {@code invalidEntities} sibling store. Regenerated on every rebuild from blob;
 * never persisted independently (design 02 §4.3 layered model).
 *
 * <p>{@code payload} carries the parsed JSON body when deserialization succeeded
 * and the entity was rejected for a semantic reason; it is {@code null} when the
 * blob body itself failed to parse. The Configuration API surfaces the payload
 * fields on Owner-view responses so the entity remains visible to operators.
 */
@Value
public class InvalidEntityRecord {
    String simpleName;
    String canonicalId;
    String reason;
    List<ValidationWarning> validationWarnings;
    String source;
    @Nullable
    JsonNode payload;
}
