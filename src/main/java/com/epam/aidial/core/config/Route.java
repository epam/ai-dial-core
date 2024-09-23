package com.epam.aidial.core.config;

import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.http.HttpMethod;
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
    private Set<HttpMethod> methods = Set.of();
    private List<Upstream> upstreams = List.of();
    private Set<String> userRoles = Set.of();

    @Data
    public static class Response {
        private int status = HttpStatus.OK.getCode();
        private String body = "";
    }
}
