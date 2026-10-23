package com.epam.aidial.core.server.service;

import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import org.apache.commons.lang3.tuple.Pair;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The org unit documents of the shared space, by name ({@code units}, or a unit id), behind a mocked
 * {@link ResourceService}: reads, batched loads, computes and deletes see each other's effects, and every write
 * changes the document's etag.
 */
class FakeOrgUnitStorage {

    final ResourceService resources = mock(ResourceService.class);
    final Map<String, String> documents = new HashMap<>();
    private int version;

    @SuppressWarnings("unchecked")
    FakeOrgUnitStorage(Map<String, String> initial) {
        documents.putAll(initial);
        when(resources.getResourceWithMetadata(any(), any())).thenAnswer(invocation -> read(invocation.getArgument(0)));
        when(resources.computeResource(any(), any(Function.class))).thenAnswer(invocation -> {
            ResourceDescriptor descriptor = invocation.getArgument(0);
            Function<String, String> change = invocation.getArgument(1);
            String before = documents.get(descriptor.getName());
            String after = change.apply(before);
            if (after != null && !after.equals(before)) {
                documents.put(descriptor.getName(), after);
                version++;
            }
            return null;
        });
        when(resources.deleteResource(any(), any())).thenAnswer(invocation -> {
            ResourceDescriptor descriptor = invocation.getArgument(0);
            return documents.remove(descriptor.getName()) != null;
        });
        doAnswer(invocation -> {
            List<ResourceItemMetadata> items = invocation.getArgument(0);
            List<Pair<ResourceItemMetadata, String>> result = invocation.getArgument(1);
            for (ResourceItemMetadata item : items) {
                Pair<ResourceItemMetadata, String> found = read(item.getDescriptor());
                if (found != null) {
                    result.add(found);
                }
            }
            return null;
        }).when(resources).load(anyList(), anyList());
    }

    private Pair<ResourceItemMetadata, String> read(ResourceDescriptor descriptor) {
        String body = documents.get(descriptor.getName());
        if (body == null) {
            return null;
        }
        ResourceItemMetadata metadata = new ResourceItemMetadata(descriptor)
                .setEtag("\"" + descriptor.getName() + "-" + version + "-" + body.hashCode() + "\"");
        return Pair.of(metadata, body);
    }
}
