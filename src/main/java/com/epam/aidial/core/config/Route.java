package com.epam.aidial.core.config;

import lombok.Data;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Data
public class Route {

    private String name;
    private Response response;
    private boolean rewritePath;
    private List<Pattern> paths = List.of();
    private Set<String> methods = Set.of();
    private List<Upstream> upstreams = List.of();
    private Set<String> userRoles;

    @Data
    public static class Response {
        private int status = 200;
        private String body = "";
    }
}
