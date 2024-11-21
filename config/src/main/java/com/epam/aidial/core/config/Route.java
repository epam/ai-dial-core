package com.epam.aidial.core.config;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Data
@EqualsAndHashCode(callSuper = true)
public class Route extends RoleBasedEntity {

    private Response response;
    private boolean rewritePath;
    private List<Pattern> paths = List.of();
    private Set<String> methods = Set.of();
    private List<Upstream> upstreams = List.of();

    @Data
    public static class Response {
        private int status = 200;
        private String body = "";
    }
}
