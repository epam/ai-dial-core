package com.epam.aidial.core.config;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum ResourceAccessType {
    READ,
    WRITE,
    SHARE,
    SHARE_LIST;

    public static final Set<ResourceAccessType> ALL = Collections.unmodifiableSet(
            EnumSet.allOf(ResourceAccessType.class));
    public static final Set<ResourceAccessType> READ_ONLY = Collections.unmodifiableSet(EnumSet.of(READ));
    public static final Set<ResourceAccessType> READ_WRITE = Collections.unmodifiableSet(EnumSet.of(READ, WRITE));
    public static final Set<ResourceAccessType> READ_WRITE_SHARE = Collections.unmodifiableSet(
            EnumSet.of(READ, WRITE, SHARE));

    /**
     * A share needs READ or WRITE: SHARE and SHARE_LIST never stand alone.
     */
    public static boolean hasReadOrWrite(Set<ResourceAccessType> permissions) {
        return permissions.contains(READ) || permissions.contains(WRITE);
    }
}
