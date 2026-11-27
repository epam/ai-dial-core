package com.epam.aidial.core.server.service.dependency;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.config.InvalidEntityRecord;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.storage.resource.ResourceTypes;

import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;

/**
 * Reads the {@link ConfigSnapshot} of one walk. The merged configuration and the skipped-entity records are read together under the lock
 * that every configuration publication holds, so they come from one publication: a rebuild between the two reads could pair an entity with
 * the wrong set of records. The file-sourced configuration belongs to another store, so it is read afterwards and is not part of that
 * guarantee.
 *
 * <p>The lock is held for two reads only, so a walk waits for a publication that is in progress and a publication waits for those two reads.
 * Call it from a worker thread.
 */
public final class ConfigSnapshotReader implements Supplier<ConfigSnapshot> {

    private final Lock publicationLock;
    private final Supplier<Map<ResourceTypes, Map<String, InvalidEntityRecord>>> invalidEntitiesSource;
    private final Supplier<Config> configSource;
    private final Supplier<Config> fileConfigSource;

    /**
     * The merged store takes the mutation lock of the {@link ApiKeyStore} as the lock of its publications.
     */
    public ConfigSnapshotReader(ApiKeyStore apiKeyStore, MergedConfigStore store) {
        this(apiKeyStore.getMutationLock(), store::getInvalidEntities, store::get, store::getFileSourcedConfig);
    }

    ConfigSnapshotReader(Lock publicationLock, Supplier<Map<ResourceTypes, Map<String, InvalidEntityRecord>>> invalidEntitiesSource,
                         Supplier<Config> configSource, Supplier<Config> fileConfigSource) {
        this.publicationLock = publicationLock;
        this.invalidEntitiesSource = invalidEntitiesSource;
        this.configSource = configSource;
        this.fileConfigSource = fileConfigSource;
    }

    @Override
    public ConfigSnapshot get() {
        Map<ResourceTypes, Map<String, InvalidEntityRecord>> invalidEntities;
        Config merged;
        publicationLock.lock();
        try {
            invalidEntities = invalidEntitiesSource.get();
            merged = configSource.get();
        } finally {
            publicationLock.unlock();
        }
        return new ConfigSnapshot(merged, fileConfigSource.get(), invalidEntities);
    }
}
