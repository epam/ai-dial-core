package com.epam.aidial.core.storage.data;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.util.Map;

/**
 * Metadata for a whole-resource complex resource (e.g. a skill), carrying the type-specific
 * attributes (e.g. name/description/version parsed from a skill's manifest) alongside the
 * common resource metadata.
 */
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
public class ComplexResourceItemMetadata extends ResourceItemMetadata {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, Object> attributes;

    public ComplexResourceItemMetadata(ResourceDescriptor resource) {
        super(resource);
    }
}
