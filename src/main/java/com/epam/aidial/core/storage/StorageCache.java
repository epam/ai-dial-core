package com.epam.aidial.core.storage;

import com.epam.aidial.core.data.FileMetadata;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class StorageCache {

    private final ConcurrentMap<String, FileMetadata> cache = new ConcurrentHashMap<>();

    public void cache(String fileId, FileMetadata metadata) {
        cache.put(fileId, metadata);
    }

    public FileMetadata load(String fileId) {
        return cache.get(fileId);
    }

    public void remove(String fileId) {
        cache.remove(fileId);
    }
}
