package com.epam.aidial.core.config.policy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The budget instances of every cost object, derived from the policies and assignments of a
 * {@link com.epam.aidial.core.config.Config}. Immutable, so it is swapped together with the maps it came from.
 * A record whose policy is missing yields no instance.
 */
public final class CostObjectIndex {

    public static final CostObjectIndex EMPTY = new CostObjectIndex(Map.of());

    private final Map<String, List<BudgetInstance>> instancesByCostObject;

    private CostObjectIndex(Map<String, List<BudgetInstance>> instancesByCostObject) {
        this.instancesByCostObject = instancesByCostObject;
    }

    /**
     * @param policies    by path relative to the policies folder
     * @param assignments by path relative to the assignments folder, {@code <costObject>/<id>}
     */
    public static CostObjectIndex build(Map<String, Policy> policies, Map<String, PolicyAssignment> assignments) {
        Map<String, List<BudgetInstance>> instancesByCostObject = new HashMap<>();
        policies.forEach((path, policy) -> {
            String costObject = folderOf(path);
            if (policy.isSelfApplying() && costObject != null) {
                add(instancesByCostObject, costObject, new BudgetInstance(path, path, policy, null, policy.getSubject()));
            }
        });
        assignments.forEach((path, assignment) -> {
            Policy policy = policies.get(assignment.getPolicy());
            String costObject = folderOf(path);
            if (policy != null && costObject != null) {
                List<PolicySubjectRule> subject = policy.getSubject() != null ? policy.getSubject() : assignment.getSubject();
                add(instancesByCostObject, costObject, new BudgetInstance(path, assignment.getPolicy(), policy, assignment, subject));
            }
        });
        instancesByCostObject.replaceAll((costObject, instances) -> List.copyOf(instances));
        return new CostObjectIndex(Map.copyOf(instancesByCostObject));
    }

    public Set<String> getCostObjects() {
        return instancesByCostObject.keySet();
    }

    public List<BudgetInstance> getInstances(String costObject) {
        return instancesByCostObject.getOrDefault(costObject, List.of());
    }

    private static void add(Map<String, List<BudgetInstance>> instancesByCostObject, String costObject, BudgetInstance instance) {
        instancesByCostObject.computeIfAbsent(costObject, key -> new ArrayList<>()).add(instance);
    }

    private static String folderOf(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? null : path.substring(0, slash);
    }
}
