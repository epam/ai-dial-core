package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.storage.data.ShareMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SharedResource {
    String url;
    String author;
    /**
     * The list of users or projects who shared this resource with the current user.
     */
    @JsonDeserialize(using = SharedByDeserializer.class) // For backward compatibility with string value (action: ignore)
    List<ShareMetadata> sharedBy;
    Set<ResourceAccessType> permissions;

    public SharedResource() {
    }

    public SharedResource(
            String url, String author, List<ShareMetadata> sharedBy, Set<ResourceAccessType> permissions) {
        this.url = url;
        this.author = author;
        this.sharedBy = sharedBy;
        this.permissions = permissions;
    }

    public SharedResource withUrl(String url) {
        return new SharedResource(url, author, sharedBy, permissions);
    }

    public SharedResource withAuthor(String name) {
        return new SharedResource(url, name, sharedBy, permissions);
    }

    private SharedResource withPermissions(Set<ResourceAccessType> permissions) {
        return new SharedResource(url, author, sharedBy, permissions);
    }

    public SharedResource withReadIfNoPermissions() {
        return permissions == null || permissions.isEmpty()
                ? withPermissions(EnumSet.copyOf(ResourceAccessType.READ_ONLY))
                : this;
    }

    public SharedResource withAllIfNoPermissions() {
        return permissions == null || permissions.isEmpty()
                ? withPermissions(EnumSet.copyOf(ResourceAccessType.ALL))
                : this;
    }

    public static class SharedByDeserializer extends JsonDeserializer<List<ShareMetadata>> {
        @Override
        public List<ShareMetadata> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonToken token = parser.currentToken();
            if (token == JsonToken.START_ARRAY) {
                return parser.readValueAs(new TypeReference<List<ShareMetadata>>() {
                });
            }
            return null;
        }
    }
}
