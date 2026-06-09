package com.epam.aidial.core.config;

import com.epam.aidial.core.config.annotation.EncryptedField;
import com.epam.aidial.core.config.databind.IpAddressRangeDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

@Data
public class Key {
    @ToString.Exclude
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @EncryptedField
    private String key;
    private String project;
    private String role;
    private boolean secured;
    private List<String> roles;
    @JsonDeserialize(using = IpAddressRangeDeserializer.class)
    private IpAddressRanges allowedIpAddressRanges;

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
