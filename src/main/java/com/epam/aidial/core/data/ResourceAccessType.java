package com.epam.aidial.core.data;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum ResourceAccessType {
    READ,
    WRITE;

    public static final Set<ResourceAccessType> ALL = Collections.unmodifiableSet(
            EnumSet.allOf(ResourceAccessType.class));
    public static final Set<ResourceAccessType> READ_ONLY = Collections.unmodifiableSet(
            EnumSet.of(READ));
}
