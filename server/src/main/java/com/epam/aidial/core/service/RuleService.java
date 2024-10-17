package com.epam.aidial.core.service;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.MetadataBase;
import com.epam.aidial.core.data.Publication;
import com.epam.aidial.core.data.ResourceFolderMetadata;
import com.epam.aidial.core.data.ResourceItemMetadata;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.data.Rule;
import com.epam.aidial.core.security.RuleMatcher;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.ProxyUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.lang3.tuple.Pair;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.epam.aidial.core.storage.BlobStorageUtil.PUBLIC_BUCKET;
import static com.epam.aidial.core.storage.BlobStorageUtil.PUBLIC_LOCATION;

public class RuleService {

    private static final String RULES_NAME = "rules";

    private static final TypeReference<Map<String, List<Rule>>> RULES_TYPE = new TypeReference<>() {
    };

    private static final ResourceDescription PUBLIC_RULES = ResourceDescription.fromDecoded(
            ResourceType.RULES, PUBLIC_BUCKET, PUBLIC_LOCATION, RULES_NAME);

    /**
     * Key is updated time from the metadata. Value is decoded map (folder path, list of rules).
     */
    private final AtomicReference<Pair<Long, Map<String, List<Rule>>>> cachedRules = new AtomicReference<>();

    private final ResourceService resources;

    public RuleService(ResourceService resourceService) {
        this.resources = resourceService;
    }

    public void storeRules(Publication publication) {
        List<Rule> rules = publication.getRules();
        if (rules != null) {
            storeRules(publication.getTargetFolder(), rules);
        }
    }

    public void storeRules(String targetFolder, List<Rule> rules) {
        resources.computeResource(PUBLIC_RULES, body -> {
            Map<String, List<Rule>> rulesMap = decodeRules(body);
            List<Rule> previous = rulesMap.put(targetFolder, rules);
            return (rules.equals(previous)) ? body : encodeRules(rulesMap);
        });
    }

    public Map<String, List<Rule>> listRules(ResourceDescription resource) {
        if (!resource.isFolder() || !resource.isPublic()) {
            throw new IllegalArgumentException("Bad rule url: " + resource.getUrl());
        }

        Map<String, List<Rule>> rules = getCachedRules();
        Map<String, List<Rule>> result = new TreeMap<>();

        while (resource != null) {
            String url = ruleUrl(resource);
            List<Rule> list = rules.get(url);
            resource = resource.getParent();

            if (list != null) {
                result.put(url, list);
            }
        }

        return result;
    }


    public Set<ResourceDescription> getAllowedPublicResources(
            ProxyContext context, Set<ResourceDescription> resources) {
        resources = resources.stream()
                .filter(ResourceDescription::isPublic)
                .collect(Collectors.toUnmodifiableSet());

        if (resources.isEmpty()) {
            return Set.of();
        }

        Map<String, List<Rule>> rules = getCachedRules();
        Map<String, Boolean> cache = new HashMap<>();
        for (ResourceDescription resource : resources) {
            evaluate(context, resource, rules, cache);
        }

        return resources.stream()
                .filter(resource -> {
                    resource = resource.isFolder() ? resource : resource.getParent();
                    return resource == null || cache.get(ruleUrl(resource));
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    public void filterForbidden(ProxyContext context, ResourceDescription folder, ResourceFolderMetadata metadata) {
        if (!folder.isPublic() || !folder.isFolder()) {
            return;
        }

        Map<String, List<Rule>> rules = getCachedRules();
        Map<String, Boolean> cache = new HashMap<>();
        cache.put(ruleUrl(folder), true);

        List<? extends MetadataBase> filtered = metadata.getItems().stream().filter(item -> {
            ResourceDescription resource = ResourceDescription.fromPublicUrl(item.getUrl());
            return evaluate(context, resource, rules, cache);
        }).toList();

        metadata.setItems(filtered);
    }

    private Map<String, List<Rule>> getCachedRules() {
        ResourceItemMetadata meta = resources.getResourceMetadata(PUBLIC_RULES);
        long key = (meta == null) ? Long.MIN_VALUE : meta.getUpdatedAt();
        Pair<Long, Map<String, List<Rule>>> current = cachedRules.get();

        if (current == null || current.getKey() != key) {
            Pair<ResourceItemMetadata, String> resource = resources.getResourceWithMetadata(PUBLIC_RULES);
            Pair<Long, Map<String, List<Rule>>> next = (resource == null)
                    ? Pair.of(Long.MIN_VALUE, decodeRules(null))
                    : Pair.of(resource.getKey().getUpdatedAt(), decodeRules(resource.getValue()));

            cachedRules.compareAndSet(current, next);
            current = next;
        }

        return current.getValue();
    }

    private static boolean evaluate(ProxyContext context,
                                    ResourceDescription resource,
                                    Map<String, List<Rule>> rules,
                                    Map<String, Boolean> cache) {

        if (resource != null && !resource.isFolder()) {
            resource = resource.getParent();
        }

        if (resource == null) {
            return true;
        }

        String folderUrl = ruleUrl(resource);
        Boolean evaluated = cache.get(folderUrl);

        if (evaluated != null) {
            return evaluated;
        }

        evaluated = evaluate(context, resource.getParent(), rules, cache);

        if (evaluated) {
            List<Rule> folderRules = rules.get(folderUrl);
            evaluated = folderRules == null || RuleMatcher.match(context, folderRules);
        }

        cache.put(folderUrl, evaluated);
        return evaluated;
    }

    private static String ruleUrl(ResourceDescription resource) {
        String prefix = resource.getType().getGroup();
        return resource.getUrl().substring(prefix.length() + 1);
    }

    private static Map<String, List<Rule>> decodeRules(String json) {
        Map<String, List<Rule>> rules = ProxyUtil.convertToObject(json, RULES_TYPE);
        return (rules == null) ? new LinkedHashMap<>() : rules;
    }

    private static String encodeRules(Map<String, List<Rule>> rules) {
        return ProxyUtil.convertToString(rules);
    }
}
