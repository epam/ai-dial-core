package com.epam.aidial.core.server.service;

import com.epam.aidial.core.storage.service.LockService;

/**
 * Cluster-wide serialization for admin writes across the admin scope (both {@code public/}
 * admin-managed and {@code platform/} buckets). Every admin write path — per-entity POST/PUT/DELETE
 * and bulk {@code /v1/admin/apply} — acquires this lock around its write phase so concurrent admin
 * writes on different pods cannot interleave at the entity-set level.
 *
 * <p>Lock-ordering invariant: callers acquire the admin-write lock <strong>before</strong> any
 * per-resource {@code ResourceService} lock. Non-admin paths never acquire the admin-write lock, so
 * the consistent ordering precludes deadlock.
 *
 * <p>The single key {@link #LOCK_KEY} is intentional — admin writes are rare on real envs, and full
 * sequential ordering is preferable to per-bucket schemes that would still allow cross-bucket
 * interleaving.
 */
public class AdminWriteLockService {

    public static final String LOCK_KEY = "admin-writes";

    private final LockService lockService;

    public AdminWriteLockService(LockService lockService) {
        this.lockService = lockService;
    }

    public LockService.Lock acquire() {
        return lockService.lock(LOCK_KEY);
    }
}
