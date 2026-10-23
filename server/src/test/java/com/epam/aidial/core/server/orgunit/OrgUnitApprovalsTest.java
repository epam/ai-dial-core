package com.epam.aidial.core.server.orgunit;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.OrgUnitPermission;
import com.epam.aidial.core.config.OrgUnitRole;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.Publication;
import com.epam.aidial.core.server.data.Rule;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.server.service.RuleService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrgUnitApprovalsTest {

    private static final String TREE = """
            {"units": {"public/": {"id": "legacy"}, "public/Acme/": {"id": "a"}, "public/Acme/Risk/": {"id": "r"}}}
            """;

    private static final String LEGACY_POLICY = """
            {"roleAssignments": [{"id": "l", "roleName": "admin",
              "subject": {"type": "claimsPredicate", "source": "email", "function": "EQUAL", "targets": ["bob@acme.com"]}}]}
            """;

    private static final String ACME_POLICY = """
            {"roleAssignments": [
              {"id": "1", "roleName": "admin", "subject": {"type": "claimsPredicate", "source": "email", "function": "EQUAL", "targets": ["Bob@Acme.com"]}},
              {"id": "2", "roleName": "admin", "subject": {"type": "claimsPredicate", "source": "groups", "function": "EQUAL", "targets": ["leads"]}},
              {"id": "3", "roleName": "editor", "subject": {"type": "claimsPredicate", "source": "email", "function": "EQUAL", "targets": ["eve@acme.com"]}},
              {"id": "4", "roleName": "admin", "subject": {"type": "any"}},
              {"id": "5", "roleName": "admin", "subject": {"type": "user", "id": "mallory"}},
              {"id": "6", "roleName": "admin", "subject": {"type": "claimsPredicate", "source": "email", "function": "EQUAL", "targets": []}},
              {"id": "8", "roleName": "admin", "subject": {"type": "claimsPredicate", "source": "roles", "function": "EQUAL", "targets": ["acme-approver"]}},
              {"id": "9", "roleName": "approver", "subject": {"type": "claimsPredicate", "source": "email", "function": "EQUAL", "targets": ["olga@acme.com"]}},
              {"id": "10", "roleName": "reviewer", "subject": {"type": "claimsPredicate", "source": "email", "function": "EQUAL", "targets": ["rita@acme.com"]}},
              {"id": "11", "roleName": "admin", "subject": {"type": "claimsPredicate", "source": "email", "function": "TRUE", "targets": ["x"]}},
              {"id": "12", "roleName": "admin", "subject": {"type": "claimsPredicate", "source": "email", "function": "REGEX", "targets": [".*"]}},
              {"id": "13", "roleName": "admin", "subject": {"type": "claimsPredicate", "source": "email", "function": "EQUAL"}}]}
            """;

    private final FakeOrgUnitStorage storage = new FakeOrgUnitStorage(Map.of("units", TREE, "legacy", LEGACY_POLICY, "a", ACME_POLICY));
    private final ResourceService resources = storage.resources;
    private final EncryptionService encryption = mock(EncryptionService.class);
    private final Config config = new Config();

    @Test
    void testOnlyClaimsPredicateApproversMatch() {
        OrgUnitApprovals approvals = approvals(true);

        assertTrue(approvals.mayDecide(person("bob@acme.com"), publication("public/Acme/", "files/public/Acme/a.txt")));
        assertTrue(approvals.mayDecide(person("dave@acme.com", "groups", "leads"), publication("public/Acme/", "files/public/Acme/a.txt")));
        assertTrue(approvals.mayDecide(withRoles(person("x@acme.com"), "acme-approver"), publication("public/Acme/", "files/public/Acme/a")));
        assertFalse(approvals.mayDecide(person("eve@acme.com"), publication("public/Acme/", "files/public/Acme/a.txt")));
        assertFalse(approvals.mayDecide(person("mallory"), publication("public/Acme/", "files/public/Acme/a.txt")));
        assertFalse(approvals.approvesAnyUnit(person("nobody@acme.com")));
    }

    @Test
    void testOnlyRolesGrantingPublicationsDecide() {
        OrgUnitApprovals approvals = approvals(true);
        Publication publication = publication("public/Acme/", "files/public/Acme/a.txt");

        assertFalse(approvals.mayReview(person("eve@acme.com"), publication));
        assertFalse(approvals.approvesAnyUnit(person("eve@acme.com")));
        assertFalse(approvals.mayReview(person("olga@acme.com"), publication));
        assertFalse(approvals.approvesAnyUnit(person("olga@acme.com")));
        assertFalse(approvals.mayReview(person("rita@acme.com"), publication));

        config.setOrgUnitRoles(Map.of("reviewer", new OrgUnitRole("Reviews requests",
                List.of(new OrgUnitPermission(List.of(OrgUnitRoles.READ), "publications/**")))));
        assertTrue(approvals.mayReview(person("rita@acme.com"), publication));
        assertTrue(approvals.approvesUnitOf(person("rita@acme.com"), "public/Acme/"));
        assertFalse(approvals.mayDecide(person("rita@acme.com"), publication));
        config.setOrgUnitRoles(Map.of("reviewer", new OrgUnitRole("Decides without reading",
                List.of(new OrgUnitPermission(List.of(OrgUnitRoles.WRITE), "publications/**")))));
        assertFalse(approvals.mayDecide(person("rita@acme.com"), publication), "write alone doesn't decide");

        config.setOrgUnitRoles(Map.of("admin", new OrgUnitRole("No approvals", List.of(new OrgUnitPermission(List.of(OrgUnitRoles.WRITE), "files/**")))));
        assertFalse(approvals.mayDecide(person("bob@acme.com"), publication));
        assertFalse(approvals.mayReview(person("bob@acme.com"), publication));
    }

    @Test
    void testApplicationsApiKeysAndSwitchedOffNeverMatch() {
        Publication publication = publication("public/Acme/", "files/public/Acme/a.txt");
        ProxyContext application = person("bob@acme.com");
        application.getApiKeyData().setPerRequestKey("per-request");
        ProxyContext apiKey = context(null);

        assertFalse(approvals(true).mayDecide(application, publication));
        assertFalse(approvals(true).mayDecide(apiKey, publication));
        assertFalse(approvals(true).approvesAnyUnit(apiKey));
        assertFalse(approvals(false).mayDecide(person("bob@acme.com"), publication));
        assertFalse(approvals(false).approvesAnyUnit(person("bob@acme.com")));
    }

    @Test
    void testEveryTouchedUnitMustBeApproved() {
        OrgUnitApprovals approvals = approvals(true);
        ProxyContext bob = person("bob@acme.com");

        assertFalse(approvals.mayDecide(bob, publication("public/Acme/", "files/public/Acme/Risk/a.txt")));
        assertFalse(approvals.mayDecide(bob, publication("public/Acme/Risk/", "files/public/Acme/Risk/a.txt")));
        assertFalse(approvals.mayDecide(bob, publication("public/Handbook/", "files/public/Handbook/a.txt")));
        assertFalse(approvals.mayDecide(bob, publication("public/", "files/public/a.txt")));
        assertFalse(approvals.mayDecide(bob, publication("public/Acme/", "files/public/Acme/a.txt", "files/public/b.txt")));
        assertFalse(approvals.mayDecide(bob, publication("public/Acme/", "not a url")));
        assertTrue(approvals.approvesUnitOf(bob, "public/Acme/"));
        assertFalse(approvals.approvesUnitOf(bob, "public/"));
        assertFalse(approvals.approvesUnitOf(bob, "public/Acme/Risk/"));
    }

    @Test
    void testRequestsStillCarryingRulesAreDecidedButOwnAreNot() {
        OrgUnitApprovals approvals = approvals(true);
        ProxyContext bob = person("bob@acme.com");

        // filed before its folder became a unit: the rules are never stored inside it
        assertTrue(approvals.mayDecide(bob, publication("public/Acme/", "files/public/Acme/a.txt").setRules(List.of())));
        Rule open = new Rule();
        open.setSource("roles");
        open.setFunction(Rule.Function.TRUE);
        assertTrue(approvals.mayDecide(bob, publication("public/Acme/", "files/public/Acme/a.txt").setRules(List.of(open))));
        assertTrue(approvals.mayReview(bob, publication("public/Acme/", "files/public/Acme/a.txt").setRules(List.of())));
        assertFalse(approvals.mayDecide(bob, publication("public/Acme/", "files/public/Acme/a.txt")
                .setUrl("publications/enc-Users_bob@acme.com_/7")));
    }

    @Test
    void testReviewCopiesOfPendingRequestsOnly() {
        OrgUnitApprovals approvals = approvals(true);
        ProxyContext bob = person("bob@acme.com");
        Publication pending = publication("public/Acme/", "files/public/Acme/a.txt");
        Publication approved = publication("public/Acme/", "files/public/Acme/b.txt").setUrl("publications/enc-Users_alice_/8")
                .setStatus(Publication.Status.APPROVED);
        when(resources.getResource(argThat(descriptor -> descriptor != null && descriptor.getType() == ResourceTypes.PUBLICATION)))
                .thenReturn(ProxyUtil.convertToString(Map.of(pending.getUrl(), pending, approved.getUrl(), approved)));

        assertTrue(approvals.mayReadReviewCopy(bob, review("Users/alice/publications/7/", "a.txt")));
        assertTrue(approvals.mayReadReviewCopy(bob, review("Users/alice/publications/7/", "")));
        assertFalse(approvals.mayReadReviewCopy(bob, review("Users/alice/publications/8/", "b.txt")));
        assertFalse(approvals.mayReadReviewCopy(bob, review("Users/alice/publications/9/", "a.txt")));
        assertFalse(approvals.mayReadReviewCopy(bob, review("Users/alice/publications/7/x/", "a.txt")));
        assertFalse(approvals.mayReadReviewCopy(bob, review("Users/alice/", "a.txt")));
        assertFalse(approvals.mayReadReviewCopy(person("nobody@acme.com"), review("Users/alice/publications/7/", "a.txt")));
    }

    @Test
    void testOneUnitListReadPerDecisionAndNoRequestReadForOutsiders() {
        OrgUnitApprovals approvals = approvals(true);
        String[] targets = new String[20];
        for (int i = 0; i < targets.length; i++) {
            targets[i] = "files/public/Acme/f" + i + ".txt";
        }
        Publication publication = publication("public/Acme/", targets);

        clearInvocations(resources);
        assertTrue(approvals.mayReview(person("bob@acme.com"), publication));
        verify(resources, times(1)).getResourceWithMetadata(argThat(descriptor -> descriptor.getName().equals("units")), any());
        verify(resources, times(1)).getResourceWithMetadata(argThat(descriptor -> descriptor.getName().equals("a")), any());
        verify(resources, times(2)).getResourceWithMetadata(any(), any());

        clearInvocations(resources);
        assertFalse(approvals.mayReadReviewCopy(person("nobody@acme.com"), review("Users/alice/publications/7/", "a.txt")));
        verify(resources, never()).getResource(any());
        verify(encryption, never()).encrypt(anyString());
    }

    private OrgUnitApprovals approvals(boolean enabled) {
        when(encryption.encrypt(anyString())).thenAnswer(invocation -> "enc-" + invocation.<String>getArgument(0).replace("/", "_"));
        OrgUnitService units = new OrgUnitService(resources, mock(RuleService.class), mock(LockService.class),
                new OrgUnitSettings(enabled), () -> "id", () -> 0L);
        return new OrgUnitApprovals(units, encryption, resources);
    }

    private static Publication publication(String targetFolder, String... targets) {
        List<Publication.Resource> resources = new ArrayList<>();
        for (String target : targets) {
            Publication.Resource resource = new Publication.Resource();
            resource.setAction(Publication.ResourceAction.ADD);
            resource.setTargetUrl(target);
            resources.add(resource);
        }
        return new Publication().setUrl("publications/enc-Users_alice_/7").setTargetFolder(targetFolder)
                .setStatus(Publication.Status.PENDING).setResources(resources);
    }

    private static ResourceDescriptor review(String location, String path) {
        return ResourceDescriptorFactory.fromDecoded(ResourceTypes.FILE, "enc:" + location, location, path);
    }

    private ProxyContext person(String email, String... claims) {
        ObjectNode node = ProxyUtil.MAPPER.createObjectNode().put("email", email);
        for (int i = 0; i < claims.length; i += 2) {
            if (claims[i].equals("groups")) {
                node.putArray("groups").add(claims[i + 1]);
            } else {
                node.put(claims[i], claims[i + 1]);
            }
        }
        return context(new ExtractedClaims(email, List.of(), email, node, null, email));
    }

    private ProxyContext withRoles(ProxyContext context, String role) {
        when(context.getUserRoles()).thenReturn(List.of(role));
        return context;
    }

    private ProxyContext context(ExtractedClaims claims) {
        ProxyContext context = mock(ProxyContext.class);
        when(context.getExtractedClaims()).thenReturn(claims);
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(context.getUserId()).thenReturn(claims == null ? null : claims.userId());
        when(context.getProject()).thenReturn(claims == null ? "project" : null);
        when(context.getUserRoles()).thenReturn(List.of());
        when(context.getConfig()).thenReturn(config);
        return context;
    }
}
