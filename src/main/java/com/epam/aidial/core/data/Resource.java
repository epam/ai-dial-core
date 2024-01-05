package com.epam.aidial.core.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Resource {

    private String path;
    private String body;
    private Long createdAt;
    private Long updatedAt;

    public Resource(String path, String body) {
        this(path, body, null, null);
    }

    public Resource(String path, String body, Long createdAt, Long updatedAt) {
        this.path = path;
        this.body = body;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}