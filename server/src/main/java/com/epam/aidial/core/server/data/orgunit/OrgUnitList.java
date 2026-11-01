package com.epam.aidial.core.server.data.orgunit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The unit list document. Keys are unit folders of the shared space, decoded and ending in
 * {@code /}, the same for every resource type: {@code public/Acme/Risk/}. The shared space itself, {@code public/},
 * is never a unit.
 * Keys are kept sorted, so a unit always comes after its parent.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrgUnitList(Map<String, OrgUnit> units) {

    public OrgUnitList {
        units = units == null ? new TreeMap<>() : units instanceof SortedMap<String, OrgUnit> ? units : new TreeMap<>(units);
    }

    public OrgUnitList readOnly() {
        return new OrgUnitList(Collections.unmodifiableSortedMap(new TreeMap<>(units)));
    }
}
