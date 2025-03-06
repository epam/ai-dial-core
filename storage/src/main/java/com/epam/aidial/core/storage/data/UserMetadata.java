package com.epam.aidial.core.storage.data;

import lombok.Data;

@Data
public class UserMetadata {
    private String author;
    private Long createdAt;
    private Long updatedAt;
    private String etag;
    private String resourceType;
}
