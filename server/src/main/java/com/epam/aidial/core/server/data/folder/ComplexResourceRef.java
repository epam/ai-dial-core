package com.epam.aidial.core.server.data.folder;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sweep-enumeration pointer stored at {@code complex_resource_refs/{uuid}.json}, written once on first
 * whole-resource creation so {@code ComplexResourceSweepService} can enumerate every complex resource in the
 * system without walking every bucket. The marker (not this pointer) remains the source of truth for the
 * resource's state/version.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ComplexResourceRef {
    private String url;
}
