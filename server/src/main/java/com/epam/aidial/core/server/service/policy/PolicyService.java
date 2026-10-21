package com.epam.aidial.core.server.service.policy;

import com.epam.aidial.core.config.policy.Policy;
import com.epam.aidial.core.config.policy.PolicyAssignment;
import com.epam.aidial.core.config.policy.PolicyAssignmentValidator;
import com.epam.aidial.core.config.policy.PolicyObjectSet;
import com.epam.aidial.core.config.policy.PolicySubjectRule;
import com.epam.aidial.core.server.config.ConfigPostProcessor;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceType;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import org.apache.commons.lang3.tuple.Pair;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * Stores policies and assignments and enforces the rules that involve more than one record. Every
 * method blocks on storage, and a write needs the admin bucket locks held by the caller: they are what
 * makes a rule such as "no two budgets of equal priority" hold between concurrent writers.
 *
 * <p>A rule that fails answers {@code 422}; one that fails because of another stored record answers {@code 409}.
 */
public class PolicyService {

    private static final int MAX_REFERENCES_LISTED = 5;

    private final ResourceService resourceService;
    private final StagedPolicyRecords staged;

    public PolicyService(ResourceService resourceService) {
        this(resourceService, new StagedPolicyRecords());
    }

    /**
     * @param staged records validated but not stored yet, which a record is validated against too; see {@link PolicyAssignmentPrecheck}
     */
    PolicyService(ResourceService resourceService, StagedPolicyRecords staged) {
        this.resourceService = resourceService;
        this.staged = staged;
    }

    /**
     * Resolves the descriptor of the record at {@code path} (relative to the type's folder), rejecting a
     * malformed path with {@code 400}: one folder level at most, exactly one for an assignment, and no
     * {@code /} or {@code %} inside an element.
     */
    public static ResourceDescriptor describe(ResourceType type, String path) {
        if (path == null || path.isEmpty() || path.endsWith("/")) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Resource name must not be empty or a folder");
        }
        String[] elements = path.split("/", -1);
        if (elements.length > 2) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "At most one folder level is allowed: " + path);
        }
        if (type == ResourceTypes.POLICY_ASSIGNMENT && elements.length != 2) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "An assignment path must be <costObject>/<id>: " + path);
        }
        for (String element : elements) {
            if (!isValidElement(element)) {
                throw new HttpException(HttpStatus.BAD_REQUEST, "Invalid path element '" + element + "': must match "
                        + ConfigPostProcessor.ENTITY_NAME_PATTERN.pattern() + " and must not contain %");
            }
        }
        try {
            return ResourceDescriptorFactory.fromEntityPath(type, ResourceDescriptor.PLATFORM_BUCKET,
                    ResourceDescriptor.PLATFORM_LOCATION, path);
        } catch (IllegalArgumentException e) {
            throw new HttpException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Validates and stores the policy in one step under the lock of its resource, so that the version checked
     * is the version replaced.
     */
    public ResourceItemMetadata putPolicy(String path, Policy policy, EtagHeader etag, String author) {
        ResourceDescriptor descriptor = describe(ResourceTypes.POLICY, path);
        return resourceService.computeResource(descriptor, etag, author, existing -> {
            checkPolicy(path, policy, existing != null);
            policy.setId(path);
            return PolicyRecordCodec.write(policy);
        });
    }

    /**
     * Validates and stores the assignment in one step under the lock of its resource, so that the version
     * checked is the version replaced.
     */
    public ResourceItemMetadata putAssignment(String path, PolicyAssignment assignment, EtagHeader etag, String author) {
        ResourceDescriptor descriptor = describe(ResourceTypes.POLICY_ASSIGNMENT, path);
        return resourceService.computeResource(descriptor, etag, author, existing -> {
            checkAssignment(path, assignment, existing == null ? null : PolicyRecordCodec.readAssignment(existing));
            assignment.setId(path);
            return PolicyRecordCodec.write(assignment);
        });
    }

    /**
     * Refuses while an assignment references the policy, checked and deleted under the lock of its resource.
     *
     * @return whether the policy existed
     */
    public boolean deletePolicy(String path, EtagHeader etag) {
        ResourceDescriptor descriptor = describe(ResourceTypes.POLICY, path);
        return resourceService.computeResource(descriptor, etag, null, existing -> {
            if (existing == null) {
                return null;
            }
            List<String> references = listAssignments(referencingScope(path)).entrySet().stream()
                    .filter(entry -> path.equals(entry.getValue().getPolicy()))
                    .map(Map.Entry::getKey)
                    .sorted()
                    .toList();
            if (!references.isEmpty()) {
                throw new HttpException(HttpStatus.CONFLICT, "Policy '" + path + "' is referenced by " + references.size()
                        + " assignment(s), for example " + references.subList(0, Math.min(references.size(), MAX_REFERENCES_LISTED)));
            }
            return null;
        }) != null;
    }

    /**
     * @return whether the assignment existed
     */
    public boolean deleteAssignment(String path, EtagHeader etag) {
        return resourceService.deleteResource(describe(ResourceTypes.POLICY_ASSIGNMENT, path), etag);
    }

    /**
     * Checks a policy against the stored records and those staged, without writing it.
     */
    public void validatePolicy(String path, Policy policy) {
        checkPolicy(path, policy, resourceService.hasResource(describe(ResourceTypes.POLICY, path)));
    }

    /**
     * Checks an assignment against the stored records and those staged, without writing it.
     */
    public void validateAssignment(String path, PolicyAssignment assignment) {
        String body = resourceService.getResource(describe(ResourceTypes.POLICY_ASSIGNMENT, path));
        checkAssignment(path, assignment, body == null ? null : PolicyRecordCodec.readAssignment(body));
    }

    /**
     * @param exists whether a policy is stored at the path; only then can an assignment already refer to it
     */
    private void checkPolicy(String path, Policy policy, boolean exists) {
        describe(ResourceTypes.POLICY, path);
        requireIdMatches(policy.getId(), path);
        reject(PolicyAssignmentValidator.validatePolicy(policy));
        String folder = folderOf(path);
        if (policy.getCostObject() != null) {
            requireValidElement("costObject", policy.getCostObject());
            if (folder != null && !folder.equals(policy.getCostObject())) {
                throw unprocessable("costObject '" + policy.getCostObject() + "' differs from the folder '" + folder + "' of the policy");
            }
        }
        if (policy.isSelfApplying()) {
            if (folder == null) {
                throw unprocessable("A policy that pins both subject and costObject applies by itself "
                        + "and must live in the folder of its cost object");
            }
            if (staged.assignments.containsKey(path) || resourceService.hasResource(describe(ResourceTypes.POLICY_ASSIGNMENT, path))) {
                throw new HttpException(HttpStatus.CONFLICT, "'" + path + "' is already used by an assignment of the cost object");
            }
        }
        // The assignments of the policy take its new values: they must stay valid and must not start to tie.
        Instances instances = new Instances(Map.of(path, policy), Map.of());
        List<String> costObjects = new ArrayList<>();
        if (policy.isSelfApplying()) {
            costObjects.add(folder);
        }
        if (exists) {
            for (Map.Entry<String, PolicyAssignment> entry : listAssignments(referencingScope(path)).entrySet()) {
                if (!path.equals(entry.getValue().getPolicy())) {
                    continue;
                }
                String costObject = folderOf(entry.getKey());
                String problem = pinProblem(policy, entry.getValue(), costObject);
                if (problem != null) {
                    throw unprocessable("Assignment '" + entry.getKey() + "' would become invalid: " + problem);
                }
                if (!costObjects.contains(costObject)) {
                    costObjects.add(costObject);
                }
            }
        }
        for (String costObject : costObjects) {
            instances.requireNoTie(costObject, path);
        }
    }

    private void checkAssignment(String path, PolicyAssignment assignment, @Nullable PolicyAssignment previous) {
        describe(ResourceTypes.POLICY_ASSIGNMENT, path);
        requireIdMatches(assignment.getId(), path);
        reject(PolicyAssignmentValidator.validateAssignment(assignment));
        String folder = folderOf(path);
        if (assignment.getCostObject() != null) {
            requireValidElement("costObject", assignment.getCostObject());
        }
        String policyPath = assignment.getPolicy();
        try {
            describe(ResourceTypes.POLICY, policyPath);
        } catch (HttpException e) {
            throw unprocessable("policy '" + policyPath + "' is not a valid policy path: " + e.getMessage());
        }
        Policy policy = findPolicy(policyPath);
        if (policy == null) {
            throw unprocessable("Policy '" + policyPath + "' does not exist");
        }
        String policyFolder = folderOf(policyPath);
        if (policyFolder != null && !policyFolder.equals(folder)) {
            throw unprocessable("Policy '" + policyPath + "' is a project policy of cost object '" + policyFolder + "'");
        }
        String problem = pinProblem(policy, assignment, folder);
        if (problem != null) {
            throw unprocessable(problem);
        }
        if (assignment.getExpiresAt() != null
                && (previous == null || !assignment.getExpiresAt().equals(previous.getExpiresAt()))
                && !OffsetDateTime.parse(assignment.getExpiresAt()).isAfter(OffsetDateTime.now())) {
            throw unprocessable("expiresAt must be in the future");
        }
        Policy sameName = findPolicy(path);
        if (sameName != null && sameName.isSelfApplying()) {
            throw new HttpException(HttpStatus.CONFLICT, "'" + path + "' is already used by a self-applying policy of the cost object");
        }
        new Instances(Map.of(), Map.of(path, assignment)).requireNoTie(folder, policyPath);
    }

    private static String pinProblem(Policy policy, PolicyAssignment assignment, String folder) {
        if ((policy.getSubject() != null) == (assignment.getSubject() != null)) {
            return policy.getSubject() != null
                    ? "subject is pinned by both the policy and the assignment"
                    : "subject is pinned by neither the policy nor the assignment";
        }
        if ((policy.getCostObject() != null) == (assignment.getCostObject() != null)) {
            return policy.getCostObject() != null
                    ? "costObject is pinned by both the policy and the assignment"
                    : "costObject is pinned by neither the policy nor the assignment";
        }
        String costObject = policy.getCostObject() != null ? policy.getCostObject() : assignment.getCostObject();
        if (!costObject.equals(folder)) {
            return "costObject '" + costObject + "' differs from the folder '" + folder + "' of the assignment";
        }
        return null;
    }

    @Nullable
    private Policy findPolicy(String path) {
        Policy policy = staged.policies.get(path);
        if (policy != null) {
            return policy;
        }
        String body = resourceService.getResource(describe(ResourceTypes.POLICY, path));
        return body == null ? null : PolicyRecordCodec.readPolicy(body);
    }

    /**
     * Where assignments of the policy can live: the folder of a project policy, anywhere for a global one.
     */
    @Nullable
    private static String referencingScope(String policyPath) {
        return folderOf(policyPath);
    }

    private Map<String, PolicyAssignment> listAssignments(@Nullable String folder) {
        Map<String, PolicyAssignment> assignments = list(ResourceTypes.POLICY_ASSIGNMENT, folder, PolicyRecordCodec::readAssignment);
        staged.assignments.forEach((path, assignment) -> {
            if (folder == null || folder.equals(folderOf(path))) {
                assignments.put(path, assignment);
            }
        });
        return assignments;
    }

    private Map<String, Policy> listPolicies(String folder) {
        Map<String, Policy> policies = list(ResourceTypes.POLICY, folder, PolicyRecordCodec::readPolicy);
        staged.policies.forEach((path, policy) -> {
            if (folder.equals(folderOf(path))) {
                policies.put(path, policy);
            }
        });
        return policies;
    }

    private <T> Map<String, T> list(ResourceTypes type, @Nullable String folder, Function<String, T> reader) {
        ResourceDescriptor root = ResourceDescriptorFactory.fromDecoded(type, ResourceDescriptor.PLATFORM_BUCKET,
                ResourceDescriptor.PLATFORM_LOCATION, folder == null ? "/" : folder + "/");
        Map<String, T> records = new HashMap<>();
        for (Pair<ResourceItemMetadata, String> item : resourceService.listResources(root, listed -> { })) {
            ResourceItemMetadata metadata = item.getLeft();
            String parent = metadata.getParentPath();
            records.put(parent == null ? metadata.getName() : parent + "/" + metadata.getName(), reader.apply(item.getRight()));
        }
        return records;
    }

    @Nullable
    private static String folderOf(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? null : path.substring(0, slash);
    }

    private static boolean isValidElement(String element) {
        return ConfigPostProcessor.ENTITY_NAME_PATTERN.matcher(element).matches() && element.indexOf('%') < 0;
    }

    private static void requireValidElement(String field, String value) {
        if (!isValidElement(value)) {
            throw unprocessable(field + " '" + value + "' must match " + ConfigPostProcessor.ENTITY_NAME_PATTERN.pattern()
                    + " and must not contain %");
        }
    }

    private static void requireIdMatches(String id, String path) {
        if (id != null && !id.equals(path)) {
            throw unprocessable("id '" + id + "' differs from the path '" + path + "'");
        }
    }

    private static void reject(@Nullable String error) {
        if (error != null) {
            throw unprocessable(error);
        }
    }

    private static HttpException unprocessable(String message) {
        return new HttpException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    private record Instance(String id, String policyPath, int priority, PolicyObjectSet object, List<PolicySubjectRule> subject) {
    }

    /**
     * The budget instances of a cost object — its assignments and the self-applying policies of its
     * folder — read once per cost object, with the record being validated standing in for its stored
     * version. An expired assignment is no instance: it can pay nothing.
     */
    private final class Instances {

        private final Map<String, Policy> policyOverrides;
        private final Map<String, PolicyAssignment> assignmentOverrides;
        private final Map<String, Map<String, Instance>> byCostObject = new HashMap<>();
        private final Map<String, Optional<Policy>> policiesByPath = new HashMap<>();

        Instances(Map<String, Policy> policyOverrides, Map<String, PolicyAssignment> assignmentOverrides) {
            this.policyOverrides = policyOverrides;
            this.assignmentOverrides = assignmentOverrides;
        }

        /**
         * Rejects an instance of the policy that ties in priority with one of another policy and can pay the
         * same calls. Instances of one policy may share a priority, whatever their subjects.
         */
        void requireNoTie(String costObject, String policyPath) {
            Map<String, Instance> instances = byCostObject.computeIfAbsent(costObject, this::read);
            for (Instance candidate : instances.values()) {
                if (!candidate.policyPath().equals(policyPath)) {
                    continue;
                }
                for (Instance other : instances.values()) {
                    if (!other.policyPath().equals(policyPath) && other.priority() == candidate.priority()
                            && other.object().intersects(candidate.object())
                            && PolicyAssignmentValidator.subjectsOverlap(other.subject(), candidate.subject())) {
                        throw new HttpException(HttpStatus.CONFLICT, "Budget '" + other.id() + "' has the same priority "
                                + candidate.priority() + " as '" + candidate.id() + "' in cost object '" + costObject
                                + "' and can pay the same calls; give them different priorities");
                    }
                }
            }
        }

        private Map<String, Instance> read(String costObject) {
            Map<String, Instance> instances = new HashMap<>();
            Map<String, Policy> policies = listPolicies(costObject);
            policyOverrides.forEach((path, policy) -> {
                if (costObject.equals(folderOf(path))) {
                    policies.put(path, policy);
                }
            });
            policies.forEach((path, policy) -> {
                if (policy.isSelfApplying()) {
                    instances.put(path, new Instance(path, path, policy.getPriority(), policy.getObject(), policy.getSubject()));
                }
            });
            Map<String, PolicyAssignment> assignments = listAssignments(costObject);
            assignmentOverrides.forEach((path, assignment) -> {
                if (costObject.equals(folderOf(path))) {
                    assignments.put(path, assignment);
                }
            });
            OffsetDateTime now = OffsetDateTime.now();
            assignments.forEach((path, assignment) -> {
                if (assignment.getExpiresAt() != null && !OffsetDateTime.parse(assignment.getExpiresAt()).isAfter(now)) {
                    return;
                }
                Policy policy = policyOf(assignment.getPolicy());
                if (policy != null) {
                    List<PolicySubjectRule> subject = policy.getSubject() != null ? policy.getSubject() : assignment.getSubject();
                    instances.put(path, new Instance(path, assignment.getPolicy(), policy.getPriority(), policy.getObject(), subject));
                }
            });
            return instances;
        }

        @Nullable
        private Policy policyOf(String path) {
            Policy override = policyOverrides.get(path);
            if (override != null) {
                return override;
            }
            return policiesByPath.computeIfAbsent(path, key -> Optional.ofNullable(findPolicy(key))).orElse(null);
        }
    }
}
