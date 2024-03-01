package com.epam.aidial.core.data;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Set;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Conversation {
    /**
     * Conversation unique identifier
     */
    String id;
    /**
     * Path to the folder where conversation located according to the user's root
     */
    String folderId;
    /**
     * Display name
     */
    String name;
    /**
     * System prompt
     */
    String prompt;
    int temperature;
    long lastActivityDate;
    ModelId model;
    Set<String> selectedAddons;
    List<Message> messages;

    @JsonCreator
    public Conversation(@JsonProperty(value = "id", required = true) String id,
                        @JsonProperty(value = "folderId", required = true) String folderId,
                        @JsonProperty(value = "name", required = true) String name,
                        @JsonProperty(value = "prompt", required = true) String prompt,
                        @JsonProperty(value = "temperature", required = true) int temperature,
                        @JsonProperty(value = "lastActivityDate", required = true) long lastActivityDate,
                        @JsonProperty(value = "model", required = true) ModelId model,
                        @JsonProperty(value = "selectedAddons", required = true) Set<String> selectedAddons,
                        @JsonProperty(value = "messages", required = true) List<Message> messages) {
        this.id = id;
        this.folderId = folderId;
        this.name = name;
        this.prompt = prompt;
        this.temperature = temperature;
        this.lastActivityDate = lastActivityDate;
        this.model = model;
        this.selectedAddons = selectedAddons;
        this.messages = messages;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelId {
        /**
         * Application/model unique identifier
         */
        String id;

        @JsonCreator
        public ModelId(@JsonProperty(value = "id", required = true) String id) {
            this.id = id;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {
        String role;
        String content;
        ModelId model;
        MessageSettings settings;

        @JsonCreator
        public Message(@JsonProperty(value = "role", required = true) String role,
                       @JsonProperty(value = "content", required = true) String content,
                       @JsonProperty(value = "model", required = false) ModelId model,
                       @JsonProperty(value = "settings", required = false) MessageSettings settings) {
            this.role = role;
            this.content = content;
            this.model = model;
            this.settings = settings;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MessageSettings {
        String prompt;
        int temperature;
        Set<String> selectedAddons;

        @JsonCreator
        public MessageSettings(@JsonProperty(value = "prompt", required = true) String prompt,
                               @JsonProperty(value = "temperature", required = true) int temperature,
                               @JsonProperty(value = "selectedAddons", required = true) Set<String> selectedAddons) {
            this.prompt = prompt;
            this.temperature = temperature;
            this.selectedAddons = selectedAddons;
        }
    }
}
