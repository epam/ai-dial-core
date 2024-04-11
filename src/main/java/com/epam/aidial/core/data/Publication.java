package com.epam.aidial.core.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

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
}