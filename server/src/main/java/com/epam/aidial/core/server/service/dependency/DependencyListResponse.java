package com.epam.aidial.core.server.service.dependency;

import java.util.List;

/**
 * The answer of the dependency list: one entry per root, in the order of the request.
 */
public record DependencyListResponse(List<DependencyRootEntry> roots) {
}
