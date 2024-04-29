package com.epam.aidial.core.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Publication {
    /**
     * Publication url: publications/bucket/id.
     */
    String url;
    /**
     * Target directory url without resource prefix to publish to: public/ or public/folder/.
     */
    String targetUrl;
    Status status;
    Long createdAt;
    List<Resource> resources;
    Set<ResourceType> resourceTypes;
    List<Rule> rules;

    public enum Status {
        PENDING, APPROVED, REJECTED, REQUESTED_FOR_DELETION
    }

    @Data
    public static class Resource {
        /**
         * Source resource url to publish from: files/bucket/folder/file.txt.
         */
        String sourceUrl;
        /**
         * Target resource url to publish to: files/public/folder/file.
         */
        String targetUrl;
        /**
         * Review resource url to review: files/review-bucket/folder/file.txt
         */
        String reviewUrl;
    }

    public Set<ResourceType> getResourceTypes() {
        if (resourceTypes != null) {
            return resourceTypes;
        }
        if (resources == null) {
            return Set.of();
        }
        Set<ResourceType> resourceTypes = new HashSet<>();
        for (Resource resource : resources) {
            String sourceUrl = resource.getSourceUrl();
            String resourceType = sourceUrl.substring(0, sourceUrl.indexOf('/'));
            resourceTypes.add(ResourceType.of(resourceType));
        }

        return resourceTypes;
    }
}