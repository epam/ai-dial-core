package com.epam.aidial.core.server.service;

import com.epam.aidial.core.storage.blobstore.BlobStorageUtil;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.LockService;

import java.util.ArrayList;
import java.util.List;

/**
 * Cluster-wide serialization for admin writes across the admin scope (both {@code public/}
 * admin-managed and {@code platform/} buckets). Every admin write path — per-entity PUT/DELETE
 * and bulk {@code /v1/admin/apply} — acquires this lock around its write phase so concurrent admin
 * writes on different pods cannot interleave at the entity-set level.
 *
 * <p>Implemented as a composite over {@link LockService#underBucketLocks} — acquires the same
 * per-bucket Redis keys (via {@link BlobStorageUtil#toStoragePath}) in the same sorted order as
 * {@code underBucketLocks(List.of(PUBLIC_LOCATION, PLATFORM_LOCATION), …)}, but returned as an
 * {@link LockService.Lock} for try-with-resources at the controller callsites. Holding both
 * bucket locks at once means any admin write on either bucket serializes against every other
 * admin write — the cross-bucket interleaving prevention from design 02 §4.4.
 *
 * <p>Lock-ordering invariant: callers acquire the admin-write lock <strong>before</strong> any
 * per-resource {@code ResourceService} lock. Non-admin paths never acquire the admin-write lock, so
 * the consistent ordering precludes deadlock.
 */
public class AdminWriteLockService {

    public static final List<String> ADMIN_BUCKET_LOCATIONS = List.of(
            ResourceDescriptor.PUBLIC_LOCATION,
            ResourceDescriptor.PLATFORM_LOCATION);

    private final LockService lockService;

    public AdminWriteLockService(LockService lockService) {
        this.lockService = lockService;
    }

    public LockService.Lock acquire() {
        List<String> keys = ADMIN_BUCKET_LOCATIONS.stream()
                .map(bucket -> BlobStorageUtil.toStoragePath(lockService.getPrefix(), bucket))
                .distinct()
                .sorted()
                .toList();
        List<LockService.Lock> acquired = new ArrayList<>(keys.size());
        try {
            for (String key : keys) {
                acquired.add(lockService.lock(key));
            }
        } catch (RuntimeException e) {
            releaseInReverse(acquired);
            throw e;
        }
        return () -> releaseInReverse(acquired);
    }

    private static void releaseInReverse(List<LockService.Lock> locks) {
        for (int i = locks.size() - 1; i >= 0; i--) {
            locks.get(i).close();
        }
    }
}
