package com.epam.aidial.core.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Publication {
    String url;
    String sourceUrl;
    String targetUrl;
    Status status;
    Long createdAt;
    List<Resource> resources;
    List<Rule> rules;

    public enum Status {
        PENDING, APPROVED, REJECTED
    }

    @Data
    public static class Resource {
        String sourceUrl;
        String targetUrl;
        String reviewUrl;
        String version;
    }

    @Data
    public static class Rule {
        Function function;
        String source;
        List<String> targets;

        public enum Function {
            EQUAL, CONTAIN, REGEX,
        }
    }
}