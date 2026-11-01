package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * What a role assigned at an org unit grants inside that unit.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrgUnitRole {
    private String description;
    private List<OrgUnitPermission> permissions;
}
