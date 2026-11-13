package com.epam.aidial.core.server.data.permission;

import com.epam.aidial.core.config.ResourceAccessType;

import java.util.HashSet;
import java.util.Set;

/**
 * Access rights granted to a receiver on one resource by a per-request key. {@code lent} distinguishes a grant
 * written by an alias pass — the alias's authorized target — from a regular resource-dependency grant, so that
 * alias-target rights (bypassing {@code accessibleByPerRequestKey} and rate-limit role matching) are never
 * silently widened to ordinary READ grants.
 */
public record PerRequestSharedData(Set<ResourceAccessType> permissions, boolean lent) {

    public PerRequestSharedData(Set<ResourceAccessType> permissions) {
        this(permissions, false);
    }

    /**
     * A new entry that merges {@code this} and {@code other}: union of rights, lent when either is lent.
     * Returns a fresh object, so shared entries are never mutated (copy-on-write contract).
     */
    public PerRequestSharedData merge(PerRequestSharedData other) {
        Set<ResourceAccessType> union = new HashSet<>(this.permissions);
        union.addAll(other.permissions);
        return new PerRequestSharedData(union, this.lent || other.lent);
    }
}
