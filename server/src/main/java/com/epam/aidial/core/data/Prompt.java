package com.epam.aidial.core.data;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Prompt {
    /**
     * Prompt unique identifier
     */
    String id;
    /**
     * Path to the folder where prompt located according to the user's root
     */
    String folderId;
    /**
     * Display name
     */
    String name;
    /**
     * Prompt body
     */
    String content;

    @JsonCreator
    public Prompt(@JsonProperty(value = "id", required = true) String id,
                  @JsonProperty(value = "folderId", required = true) String folderId,
                  @JsonProperty(value = "name", required = true) String name,
                  @JsonProperty(value = "content", required = true) String content) {
        this.id = id;
        this.folderId = folderId;
        this.name = name;
        this.content = content;
    }
}
