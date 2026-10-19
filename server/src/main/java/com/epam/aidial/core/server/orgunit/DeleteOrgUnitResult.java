package com.epam.aidial.core.server.orgunit;

import java.util.List;

/**
 * @param removed the paths of the removed units, deepest first: {@code public/Acme/Risk/US Risk/Basel}
 */
public record DeleteOrgUnitResult(List<String> removed) {
}
