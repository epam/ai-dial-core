package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.data.orgunit.OrgUnitList;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import org.apache.commons.lang3.tuple.Pair;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads the unit tree that {@link OrgUnitService} writes. It needs nothing but the resource service, so the
 * services that check paths against units can be built before {@link OrgUnitService}.
 *
 * <p>The tree is read through the shared resource cache and decoded again only when its etag changes. An unreadable
 * tree fails the request.
 */
public class OrgUnitLookup {

    private final ResourceService resources;
    private final OrgUnitSettings settings;

    /**
     * Key is the document's etag, which changes with every write; two writes can share an update time.
     * Value is the decoded list, read-only.
     */
    private final AtomicReference<Pair<String, OrgUnitList>> cached = new AtomicReference<>();

    public OrgUnitLookup(ResourceService resources, OrgUnitSettings settings) {
        this.resources = resources;
        this.settings = settings;
    }

    public boolean isEnabled() {
        return settings.enabled();
    }

    /**
     * Refuses a skill or project whose own folder is a unit's, since it keeps its files there.
     */
    public void requireNoUnitFolder(ResourceDescriptor resource) {
        if (isUnitFolder(resource)) {
            throw new HttpException(HttpStatus.CONFLICT, "An org unit holds this path: " + resource.getUrl());
        }
    }

    /**
     * Reads the list once, so a node that cannot read it refuses to start.
     */
    public void checkOnStartup() {
        if (settings.enabled()) {
            getUnitList();
        }
    }

    /**
     * The unit list as stored now, read-only.
     */
    OrgUnitList getUnitList() {
        Pair<ResourceItemMetadata, String> resource = resources.getResourceWithMetadata(OrgUnitService.UNITS, EtagHeader.ANY);
        String key = (resource == null) ? "" : resource.getKey().getEtag();
        Pair<String, OrgUnitList> current = cached.get();

        if (current == null || !current.getKey().equals(key)) {
            OrgUnitList decoded = OrgUnitService.decode(OrgUnitService.units(OrgUnitService.parse(resource == null ? null : resource.getValue())))
                    .readOnly();
            Pair<String, OrgUnitList> next = Pair.of(key, decoded);
            cached.compareAndSet(current, next);
            current = next;
        }

        return current.getValue();
    }

    /**
     * Whether the folder of this name, the resource's own folder for an item, is a unit's: a complex resource keeps
     * its files there. Names compare ignoring case, as on create, since folders that differ only in case are one on
     * some storages.
     */
    private boolean isUnitFolder(ResourceDescriptor resource) {
        if (!isEnabled() || !resource.isPublic()) {
            return false;
        }
        String path = OrgUnitService.keyOf(resource);
        String key = resource.isFolder() ? path : path + ResourceDescriptor.PATH_SEPARATOR;
        return getUnitList().units().keySet().stream().anyMatch(key::equalsIgnoreCase);
    }
}
