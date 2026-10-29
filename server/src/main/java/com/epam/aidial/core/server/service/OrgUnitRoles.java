package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.OrgUnitPermission;
import com.epam.aidial.core.config.OrgUnitRole;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * The roles that can be assigned at an org unit. Every unit has the same ones: the built-in {@code admin},
 * {@code editor}, {@code viewer} and {@code member}, as overridden or extended by the dynamic config's
 * {@code orgUnitRoles}. A role grants only in the unit it is assigned at; a resource belongs to the nearest unit above
 * it, so child units are not covered. {@code read} on {@code org-units} shows the unit and the names of its child units,
 * never their content.
 */
public final class OrgUnitRoles {

    public static final String ADMIN = "admin";
    public static final String EDITOR = "editor";
    public static final String VIEWER = "viewer";
    public static final String MEMBER = "member";

    public static final String READ = "read";
    public static final String WRITE = "write";

    public static final String ROLE_ASSIGNMENTS = "role-assignments";
    public static final String PUBLICATIONS = "publications";
    public static final String ORG_UNITS = "org-units";

    static final List<String> CONTENT_NAMESPACES = List.of("files", "prompts", "conversations", "applications", "toolsets", "skills",
            "projects");

    private static final Set<String> ACTIONS = Set.of(READ, WRITE);
    private static final Set<String> NAMESPACES;
    private static final String ANY_BELOW = "/**";
    private static final Pattern ROLE_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Map<String, OrgUnitRole> DEFAULTS;

    static {
        Set<String> namespaces = new HashSet<>(CONTENT_NAMESPACES);
        namespaces.add(ROLE_ASSIGNMENTS);
        namespaces.add(PUBLICATIONS);
        namespaces.add(ORG_UNITS);
        NAMESPACES = Set.copyOf(namespaces);

        List<String> readWrite = List.of(READ, WRITE);
        List<String> read = List.of(READ);
        Map<String, OrgUnitRole> defaults = new LinkedHashMap<>();
        defaults.put(ADMIN, new OrgUnitRole("Reads and changes everything in the unit, decides publication requests into it"
                + " and manages its role assignments", permissions(readWrite, List.of(PUBLICATIONS, ROLE_ASSIGNMENTS))));
        defaults.put(EDITOR, new OrgUnitRole("Reads and changes everything in the unit", permissions(readWrite, List.of())));
        defaults.put(VIEWER, new OrgUnitRole("Reads everything in the unit", permissions(read, List.of())));
        defaults.put(MEMBER, new OrgUnitRole("Sees the unit and its child units, not their content",
                List.of(new OrgUnitPermission(read, ORG_UNITS + ANY_BELOW))));
        DEFAULTS = Collections.unmodifiableMap(defaults);
    }

    private final Map<String, OrgUnitRole> roles;
    private final Map<String, Map<String, Set<String>>> grants;

    private OrgUnitRoles(Map<String, OrgUnitRole> roles) {
        this.roles = roles;
        Map<String, Map<String, Set<String>>> grants = new HashMap<>();
        roles.forEach((name, role) -> {
            Map<String, Set<String>> byNamespace = new HashMap<>();
            for (OrgUnitPermission permission : role.getPermissions()) {
                Set<String> actions = byNamespace.computeIfAbsent(namespace(permission.getPathPattern()), key -> new HashSet<>());
                actions.addAll(permission.getActions());
            }
            grants.put(name, byNamespace);
        });
        this.grants = grants;
    }

    /**
     * The built-in roles, then the config's own in name order; a config role named like a built-in one replaces it.
     */
    public static OrgUnitRoles of(Config config) {
        Map<String, OrgUnitRole> configured = config.getOrgUnitRoles() == null ? Map.of() : config.getOrgUnitRoles();
        Map<String, OrgUnitRole> roles = new LinkedHashMap<>(DEFAULTS);
        roles.putAll(new TreeMap<>(configured));
        return new OrgUnitRoles(Collections.unmodifiableMap(roles));
    }

    /**
     * Refuses a config whose org unit roles core can't evaluate, so a typo never grants more or less than written.
     */
    public static void validate(@Nullable Map<String, OrgUnitRole> roles) {
        if (roles == null) {
            return;
        }

        roles.forEach((name, role) -> {
            String prefix = "orgUnitRoles." + name;
            if (name == null || !ROLE_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Org unit role names are 1 to 64 letters, digits, '_' or '-': " + name);
            }
            if (role == null || role.getPermissions() == null) {
                throw new IllegalArgumentException(prefix + " must have permissions");
            }
            for (OrgUnitPermission permission : role.getPermissions()) {
                if (permission == null || permission.getActions() == null || permission.getActions().isEmpty()
                        || permission.getActions().stream().anyMatch(action -> action == null || !ACTIONS.contains(action))) {
                    throw new IllegalArgumentException(prefix + ": actions are one or more of read, write");
                }
                String namespace = namespace(permission.getPathPattern());
                if (namespace == null) {
                    throw new IllegalArgumentException(prefix + ": pathPattern is <namespace>/** with a namespace of "
                            + String.join(", ", NAMESPACES.stream().sorted().toList()) + ", not " + permission.getPathPattern());
                }
                if (namespace.equals(ORG_UNITS) && permission.getActions().contains(WRITE)) {
                    throw new IllegalArgumentException(prefix + ": org-units/** takes only read; only DIAL admins change units");
                }
            }
        });
    }

    public Map<String, OrgUnitRole> roles() {
        return roles;
    }

    public boolean exists(String roleName) {
        return roles.containsKey(roleName);
    }

    /**
     * Whether any of the roles grants the action on the namespace. A role core doesn't know grants nothing.
     */
    public boolean allows(Collection<String> roleNames, String action, String namespace) {
        for (String roleName : roleNames) {
            Set<String> actions = grants.getOrDefault(roleName, Map.of()).get(namespace);
            if (actions != null && actions.contains(action)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the roles together grant everything the role does: who holds them may give or take it at the unit.
     */
    public boolean covers(Collection<String> roleNames, @Nullable String roleName) {
        Map<String, Set<String>> wanted = roleName == null ? null : grants.get(roleName);
        if (wanted == null) {
            return roleName != null && !roles.containsKey(roleName);
        }
        return wanted.entrySet().stream().allMatch(entry -> entry.getValue().stream()
                .allMatch(action -> allows(roleNames, action, entry.getKey())));
    }

    /**
     * The actions on every content namespace and the extra ones, then {@code read} on {@code org-units}: whoever holds a
     * built-in role at a unit sees its child units.
     */
    private static List<OrgUnitPermission> permissions(List<String> actions, List<String> extraNamespaces) {
        List<OrgUnitPermission> permissions = new ArrayList<>();
        for (String namespace : CONTENT_NAMESPACES) {
            permissions.add(new OrgUnitPermission(actions, namespace + ANY_BELOW));
        }
        for (String namespace : extraNamespaces) {
            permissions.add(new OrgUnitPermission(actions, namespace + ANY_BELOW));
        }
        permissions.add(new OrgUnitPermission(List.of(READ), ORG_UNITS + ANY_BELOW));
        return List.copyOf(permissions);
    }

    @Nullable
    private static String namespace(@Nullable String pathPattern) {
        if (pathPattern == null || !pathPattern.endsWith(ANY_BELOW)) {
            return null;
        }
        String namespace = pathPattern.substring(0, pathPattern.length() - ANY_BELOW.length());
        return NAMESPACES.contains(namespace) ? namespace : null;
    }
}
