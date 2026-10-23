package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Grants {@code actions} on the resources matching {@code pathPattern}, relative to the unit: {@code files/**} is
 * every file in the unit's folder.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrgUnitPermission {
    private List<String> actions = List.of();
    private String pathPattern;
}
