package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class Key {
    private String key;
    private String project;
    private String role;
    private boolean secured;
    private List<String> roles;

    @JsonIgnore
    public List<String> getMergedRoles() {
        List<String> result = new ArrayList<>();
        if (roles != null) {
            result.addAll(roles);
        }
        if (role != null) {
            result.add(role);
        }
        return result;
    }
}
