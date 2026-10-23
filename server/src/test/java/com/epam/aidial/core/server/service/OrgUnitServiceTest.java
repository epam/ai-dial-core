package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.data.Rule;
import com.epam.aidial.core.server.data.orgunit.OrgUnit;
import com.epam.aidial.core.server.data.orgunit.OrgUnitData;
import com.epam.aidial.core.server.data.orgunit.OrgUnitList;
import com.epam.aidial.core.server.data.orgunit.RoleAssignment;
import com.epam.aidial.core.server.data.orgunit.Subject;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrgUnitServiceTest {

    private static final OrgUnitRoles ROLES = OrgUnitRoles.of(new Config());

    private static final String UNITS = """
            {"units": {
              "public/": {"id": "public", "displayName": "Org"},
              "public/Risk/": {"id": "r", "displayName": "Risk"},
              "public/Risk/US Risk/": {"id": "u", "displayName": "US Risk"},
              "public/Risk/US Risk/Basel/": {"id": "b", "displayName": "Basel"}
            }}
            """;

    @Test
    void testWritesWithinOneMillisecondAreSeen() {
        ResourceService resources = mock(ResourceService.class);
        ResourceItemMetadata first = metadata("\"1\"");
        ResourceItemMetadata second = metadata("\"2\"");
        String withoutBasel = UNITS.replace("public/Risk/US Risk/Basel/", "public/Risk/Other/");
        when(resources.getResourceWithMetadata(any(), any())).thenReturn(Pair.of(first, withoutBasel), Pair.of(second, UNITS));
        OrgUnitService service = new OrgUnitService(resources, mock(RuleService.class), locks(), new OrgUnitSettings(true), () -> "id", () -> 0L);

        assertFalse(service.getUnitList().units().containsKey("public/Risk/US Risk/Basel/"));
        assertTrue(service.getUnitList().units().containsKey("public/Risk/US Risk/Basel/"));
    }

    @Test
    void testCachedListIsReadOnly() {
        OrgUnitList list = service(UNITS).getUnitList();
        assertThrows(UnsupportedOperationException.class, () -> list.units().remove("public/Risk/"));
    }

    @Test
    void testListWithTenantFieldStillReads() {
        OrgUnitService service = service(UNITS.replace("{\"units\"", "{\"tenant\": \"acme\", \"units\""));
        assertTrue(service.getUnitList().units().containsKey("public/Risk/"));
    }

    @Test
    void testSharedSpaceIsNotUnit() {
        ResourceService resources = mock(ResourceService.class);
        ResourceItemMetadata metadata = metadata("\"1\"");
        when(resources.getResourceWithMetadata(any(), any())).thenReturn(Pair.of(metadata, UNITS));
        AtomicReference<String> written = new AtomicReference<>();
        when(resources.computeResource(any(), any(Function.class))).thenAnswer(invocation -> {
            Function<String, String> change = invocation.getArgument(1);
            written.set(change.apply(UNITS));
            return null;
        });
        RuleService rules = mock(RuleService.class);
        OrgUnitService service = new OrgUnitService(resources, rules, locks(), new OrgUnitSettings(true), () -> "id", () -> 0L);

        OrgUnitData root = service.read(List.of("public"), false, null, true);
        assertEquals("public", root.displayName());
        assertEquals(List.of("Risk"), root.items().stream().map(OrgUnitData.Summary::name).toList());

        service.create(List.of("public"), "Acme", null, "admin");
        OrgUnitList stored = ProxyUtil.convertToObject(written.get(), OrgUnitList.class);
        assertFalse(stored.units().containsKey(OrgUnitService.SHARED_SPACE_KEY));
        assertEquals("Acme", stored.units().get("public/Acme/").displayName());
    }

    @Test
    void testCreateRunsUnderTheSharedSpaceLockAndAnswersWithWhatItWrote() {
        ResourceService resources = mock(ResourceService.class);
        ResourceItemMetadata metadata = metadata("\"1\"");
        when(resources.getResourceWithMetadata(any(), any())).thenReturn(Pair.of(metadata, "{\"units\": {}}"));
        RuleService rules = mock(RuleService.class);
        LockService locks = locks();
        OrgUnitService service = new OrgUnitService(resources, rules, locks, new OrgUnitSettings(true), () -> "new-id", () -> 0L);

        OrgUnitData created = service.create(List.of("public"), "Acme", null, "admin");

        verify(locks).underBucketLock(eq(OrgUnitService.SHARED_SPACE_KEY), any());
        assertEquals("new-id", created.id());
        assertEquals("public/Acme", created.path());
        assertEquals("admin", created.author());
    }

    @Test
    void testWritesKeepEntriesAndFieldsThisVersionDoesNotKnow() throws Exception {
        String stored = """
                {"version": 7, "units": {
                  "public/Acme/": {"id": "a", "displayName": "Acme", "approvalPolicy": "two-person",
                    "roleAssignments": [{"id": "r1", "roleName": "approver", "expiresAt": "2027-01-01",
                      "subject": {"type": "group", "name": "leads"}}]}
                }}
                """;
        AtomicReference<String> written = new AtomicReference<>();
        OrgUnitService service = writable(stored, written);

        service.create(List.of("public", "Acme"), "Risk", null, "admin");

        JsonNode document = ProxyUtil.MAPPER.readTree(written.get());
        assertEquals(7, document.get("version").intValue());
        JsonNode acme = document.get("units").get("public/Acme/");
        assertEquals("two-person", acme.get("approvalPolicy").textValue());
        assertEquals("group", acme.get("roleAssignments").get(0).get("subject").get("type").textValue());
        assertEquals("Risk", document.get("units").get("public/Acme/Risk/").get("displayName").textValue());
    }

    @Test
    void testCorruptListIsNeverReadAsEmpty() {
        for (String body : List.of("{not json", "[]", "\"text\"", "{\"units\": []}", "{\"units\": {}}{\"units\": {}}")) {
            OrgUnitService service = service(body);
            assertThrows(IllegalStateException.class, service::getUnitList, body);
        }

        ResourceService resources = mock(ResourceService.class);
        ResourceItemMetadata metadata = metadata("\"1\"");
        when(resources.getResourceWithMetadata(any(), any())).thenReturn(Pair.of(metadata, "{\"units\": {}}"));
        when(resources.computeResource(any(), any(Function.class)))
                .thenAnswer(invocation -> ((Function<String, String>) invocation.getArgument(1)).apply("{not json"));
        RuleService rules = mock(RuleService.class);
        OrgUnitService service = new OrgUnitService(resources, rules, locks(), new OrgUnitSettings(true), () -> "id", () -> 0L);

        assertThrows(IllegalStateException.class, () -> service.create(List.of("public"), "Acme", null, "admin"));
    }

    @Test
    void testMarkerIsWrittenUnderTheSharedSpaceLock() {
        AtomicReference<String> written = new AtomicReference<>();
        ResourceService resources = writableResources("{\"units\": {}}", written);
        RuleService rules = mock(RuleService.class);
        AtomicBoolean locked = new AtomicBoolean();
        LockService locks = mock(LockService.class);
        when(locks.underBucketLock(any(), any())).thenAnswer(invocation -> {
            locked.set(true);
            try {
                return ((Supplier<Object>) invocation.getArgument(1)).get();
            } finally {
                locked.set(false);
            }
        });
        AtomicBoolean markerUnderLock = new AtomicBoolean();
        when(resources.putFile(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            markerUnderLock.set(locked.get());
            return null;
        });
        OrgUnitService service = new OrgUnitService(resources, rules, locks, new OrgUnitSettings(true), () -> "id", () -> 0L);

        service.create(List.of("public"), "Acme", null, "admin");

        assertTrue(markerUnderLock.get());
    }

    @Test
    void testCreateIsSavedEvenWhenTheMarkerFails() {
        AtomicReference<String> written = new AtomicReference<>();
        ResourceService resources = writableResources("{\"units\": {}}", written);
        when(resources.putFile(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("blob store down"));
        RuleService rules = mock(RuleService.class);
        OrgUnitService service = new OrgUnitService(resources, rules, locks(), new OrgUnitSettings(true), () -> "id", () -> 0L);

        assertEquals("public/Acme", service.create(List.of("public"), "Acme", null, "admin").path());

        assertTrue(written.get().contains("public/Acme/"));
        verify(resources).putFile(any(), any(), any(), any(), any());
    }

    @Test
    void testNameTakenOnLaterPageIsRefusedAndListingStops() {
        AtomicReference<String> written = new AtomicReference<>();
        ResourceService resources = writableResources("{\"units\": {}}", written);
        ResourceDescriptor other = ResourceDescriptorFactory.fromDecoded(ResourceTypes.FILE, ResourceDescriptor.PUBLIC_BUCKET,
                ResourceDescriptor.PUBLIC_LOCATION, "Other/");
        ResourceDescriptor taken = ResourceDescriptorFactory.fromDecoded(ResourceTypes.FILE, ResourceDescriptor.PUBLIC_BUCKET,
                ResourceDescriptor.PUBLIC_LOCATION, "acme/");
        when(resources.getFolderMetadata(argThat(folder -> folder != null && folder.getType() == ResourceTypes.FILE), eq(null), anyInt(), anyBoolean()))
                .thenReturn(new ResourceFolderMetadata(other, List.of(new ResourceFolderMetadata(other)), "page-2"));
        when(resources.getFolderMetadata(argThat(folder -> folder != null && folder.getType() == ResourceTypes.FILE), eq("page-2"), anyInt(), anyBoolean()))
                .thenReturn(new ResourceFolderMetadata(taken, List.of(new ResourceFolderMetadata(taken)), null));
        RuleService rules = mock(RuleService.class);
        OrgUnitService service = new OrgUnitService(resources, rules, locks(), new OrgUnitSettings(true), () -> "id", () -> 0L);

        HttpException refused = assertThrows(HttpException.class, () -> service.create(List.of("public"), "Acme", null, "admin"));

        assertTrue(refused.getMessage().contains("already has a folder named acme"), refused.getMessage());
        verify(resources, never()).getFolderMetadata(argThat(folder -> folder != null && folder.getType() != ResourceTypes.FILE),
                any(), anyInt(), anyBoolean());
        assertNull(written.get());
    }

    @Test
    void testNameTakenInAnyTypeTreeIsRefused() {
        AtomicReference<String> written = new AtomicReference<>();
        ResourceService resources = writableResources("{\"units\": {}}", written);
        ResourceDescriptor project = ResourceDescriptorFactory.fromDecoded(ResourceTypes.PROJECT, ResourceDescriptor.PUBLIC_BUCKET,
                ResourceDescriptor.PUBLIC_LOCATION, "ACME/");
        when(resources.getFolderMetadata(argThat(folder -> folder != null && folder.getType() == ResourceTypes.PROJECT), any(), anyInt(), anyBoolean()))
                .thenReturn(new ResourceFolderMetadata(project, List.of(new ResourceFolderMetadata(project)), null));
        RuleService rules = mock(RuleService.class);
        OrgUnitService service = new OrgUnitService(resources, rules, locks(), new OrgUnitSettings(true), () -> "id", () -> 0L);

        HttpException refused = assertThrows(HttpException.class, () -> service.create(List.of("public"), "Acme", null, "admin"));

        assertEquals(HttpStatus.CONFLICT, refused.getStatus());
        assertTrue(refused.getMessage().contains("already has a folder named ACME"), refused.getMessage());
        assertNull(written.get());
    }

    @Test
    void testMarkerIsOnlyAddedToAnEmptyFolder() {
        AtomicReference<String> written = new AtomicReference<>();
        ResourceService resources = writableResources("{\"units\": {}}", written);
        ResourceDescriptor folder = ResourceDescriptorFactory.fromDecoded(ResourceTypes.FILE, ResourceDescriptor.PUBLIC_BUCKET,
                ResourceDescriptor.PUBLIC_LOCATION, "Acme/");
        ResourceDescriptor published = ResourceDescriptorFactory.fromDecoded(ResourceTypes.FILE, ResourceDescriptor.PUBLIC_BUCKET,
                ResourceDescriptor.PUBLIC_LOCATION, "Acme/report.pdf");
        when(resources.getFolderMetadata(eq(folder), any(), anyInt(), anyBoolean()))
                .thenReturn(new ResourceFolderMetadata(folder, List.of(new ResourceItemMetadata(published)), null));
        RuleService rules = mock(RuleService.class);
        OrgUnitService service = new OrgUnitService(resources, rules, locks(), new OrgUnitSettings(true), () -> "id", () -> 0L);

        service.create(List.of("public"), "Acme", null, "admin");

        verify(resources, never()).putFile(any(), any(), any(), any(), any());
    }

    @Test
    void testPartlyUnreadableEntryIsKept() {
        OrgUnitService service = service("{\"units\": {\"public/Acme/\": {\"id\": 5, \"displayName\": [\"x\"], \"createdAt\": \"soon\"}}}");

        OrgUnitData acme = service.read(List.of("public", "Acme"), false, null, true);

        assertEquals("Acme", acme.displayName());
        assertNull(acme.id());
        assertNull(acme.createdAt());
    }

    @Test
    void testNearestUnitOwnsItemOfAnyType() {
        Map<String, OrgUnit> units = service(UNITS).getUnitList().units();

        assertEquals("public/Risk/US Risk/", OrgUnitService.ownerOf(units, resource(ResourceTypes.FILE, "Risk/US Risk/Reports/Q3/var.xlsx")));
        assertEquals("public/Risk/US Risk/", OrgUnitService.ownerOf(units, resource(ResourceTypes.PROMPT, "Risk/US Risk/Templates/memo")));
        assertEquals("public/Risk/US Risk/Basel/", OrgUnitService.ownerOf(units, resource(ResourceTypes.APPLICATION, "Risk/US Risk/Basel/checkers/app")));
        assertEquals("public/Risk/", OrgUnitService.ownerOf(units, resource(ResourceTypes.FILE, "Risk/Policies/2026/limits.pdf")));
        assertEquals(OrgUnitService.SHARED_SPACE_KEY, OrgUnitService.ownerOf(units, resource(ResourceTypes.FILE, "Handbook/welcome.pdf")));
        assertEquals(OrgUnitService.SHARED_SPACE_KEY, OrgUnitService.ownerOf(units, resource(ResourceTypes.FILE, "welcome.pdf")));
        assertEquals(OrgUnitService.SHARED_SPACE_KEY, OrgUnitService.ownerOf(units, resource(ResourceTypes.FILE, "Risky/a.pdf")));
    }

    @Test
    void testMoveGuardChecksOnlyItemMovesInsideTheSharedSpace() {
        OrgUnitService service = service(UNITS);
        ResourceDescriptor inRisk = resource(ResourceTypes.FILE, "Risk/a.pdf");
        ResourceDescriptor inBasel = resource(ResourceTypes.FILE, "Risk/US Risk/Basel/a.pdf");
        ResourceDescriptor own = ResourceDescriptorFactory.fromDecoded(ResourceTypes.FILE, "bucket", "Users/alice/", "a.pdf");

        HttpException refused = assertThrows(HttpException.class, () -> service.requireSameUnit(inRisk, inBasel));
        assertEquals(HttpStatus.CONFLICT, refused.getStatus());
        service.requireSameUnit(inRisk, resource(ResourceTypes.FILE, "Risk/Policies/a.pdf"));
        service.requireSameUnit(inRisk, own);
        service.requireSameUnit(own, inBasel);
        service.requireSameUnit(resource(ResourceTypes.FILE, "Risk/Policies/"), resource(ResourceTypes.FILE, "Risk/US Risk/Basel/Policies/"));
        service.requireSameUnit(inRisk, resource(ResourceTypes.FILE, "Risk/US Risk/Basel/Policies/"));

        ResourceService resources = mock(ResourceService.class);
        new OrgUnitService(resources, mock(RuleService.class), locks(), new OrgUnitSettings(false), () -> "id", () -> 0L)
                .requireSameUnit(inRisk, inBasel);
        new OrgUnitService(resources, mock(RuleService.class), locks(), new OrgUnitSettings(true), () -> "id", () -> 0L)
                .requireSameUnit(inRisk, resource(ResourceTypes.FILE, "Risk/b.pdf"));
        verify(resources, never()).getResourceWithMetadata(any(), any());
    }

    @Test
    void testDeleteNamesUpToThreeItemsAndStopsListing() {
        ResourceService resources = writableResources("{\"units\": {\"public/Acme/\": {\"id\": \"a\"}}}", new AtomicReference<>());
        ResourceDescriptor acme = resource(ResourceTypes.FILE, "Acme/");
        when(resources.getFolderMetadata(eq(acme), eq(null), anyInt(), eq(true))).thenReturn(new ResourceFolderMetadata(acme, List.of(
                new ResourceItemMetadata(resource(ResourceTypes.FILE, "Acme/.dial_folder")),
                new ResourceItemMetadata(resource(ResourceTypes.FILE, "Acme/a.txt")),
                new ResourceItemMetadata(resource(ResourceTypes.FILE, "Acme/b.txt"))), "page-2"));
        when(resources.getFolderMetadata(eq(acme), eq("page-2"), anyInt(), eq(true))).thenReturn(new ResourceFolderMetadata(acme, List.of(
                new ResourceItemMetadata(resource(ResourceTypes.FILE, "Acme/c.txt")),
                new ResourceItemMetadata(resource(ResourceTypes.FILE, "Acme/d.txt"))), "page-3"));
        OrgUnitService service = new OrgUnitService(resources, mock(RuleService.class), locks(), new OrgUnitSettings(true), () -> "id", () -> 0L);

        HttpException refused = assertThrows(HttpException.class, () -> service.delete(List.of("public", "Acme"), false));

        assertEquals(HttpStatus.CONFLICT, refused.getStatus());
        assertTrue(refused.getMessage().contains("Acme still holds content: files/public/Acme/a.txt, files/public/Acme/b.txt, "
                + "files/public/Acme/c.txt, …"), refused.getMessage());
        verify(resources, never()).getFolderMetadata(any(), eq("page-3"), anyInt(), anyBoolean());
        verify(resources, never()).getFolderMetadata(argThat(folder -> folder != null && folder.getType() != ResourceTypes.FILE),
                any(), anyInt(), anyBoolean());
    }

    @Test
    void testMarkerOutsideTheFilesTreeIsContent() {
        ResourceService resources = writableResources("{\"units\": {\"public/Acme/\": {\"id\": \"a\"}}}", new AtomicReference<>());
        ResourceDescriptor prompts = resource(ResourceTypes.PROMPT, "Acme/");
        when(resources.getFolderMetadata(eq(prompts), any(), anyInt(), eq(true))).thenReturn(new ResourceFolderMetadata(prompts,
                List.of(new ResourceItemMetadata(resource(ResourceTypes.PROMPT, "Acme/.dial_folder"))), null));
        OrgUnitService service = new OrgUnitService(resources, mock(RuleService.class), locks(), new OrgUnitSettings(true), () -> "id", () -> 0L);

        HttpException refused = assertThrows(HttpException.class, () -> service.delete(List.of("public", "Acme"), false));

        assertTrue(refused.getMessage().contains("prompts/public/Acme/.dial_folder"), refused.getMessage());
    }

    @Test
    void testDeleteFailingOnMarkerLeavesTheUnitToRepeat() {
        AtomicReference<String> written = new AtomicReference<>();
        ResourceService resources = writableResources("{\"units\": {\"public/Acme/\": {\"id\": \"a\"}}}", written);
        ResourceDescriptor acme = resource(ResourceTypes.FILE, "Acme/");
        ResourceDescriptor marker = resource(ResourceTypes.FILE, "Acme/.dial_folder");
        when(resources.getFolderMetadata(eq(acme), any(), anyInt(), eq(true)))
                .thenReturn(new ResourceFolderMetadata(acme, List.of(new ResourceItemMetadata(marker)), null));
        when(resources.deleteResource(eq(marker), any())).thenThrow(new IllegalStateException("blob store down"));
        RuleService rules = mock(RuleService.class);
        OrgUnitService service = new OrgUnitService(resources, rules, locks(), new OrgUnitSettings(true), () -> "id", () -> 0L);

        assertThrows(IllegalStateException.class, () -> service.delete(List.of("public", "Acme"), false));

        assertNull(written.get());
        verify(rules, never()).removeRulesUnder(any());
    }

    @Test
    void testDeleteAnswersWhenTheReadRulesCantBeRemoved() {
        ResourceService resources = writableResources("{\"units\": {\"public/Acme/\": {\"id\": \"a\"}}}", new AtomicReference<>());
        RuleService rules = mock(RuleService.class);
        doThrow(new IllegalStateException("redis down")).when(rules).removeRulesUnder(any());
        OrgUnitService service = new OrgUnitService(resources, rules, locks(), new OrgUnitSettings(true), () -> "id", () -> 0L);

        assertEquals(List.of("public/Acme"), service.delete(List.of("public", "Acme"), false));
    }

    @Test
    void testDeleteRemovesTheSubtreeFromTheTreeAndItsRules() throws Exception {
        String tree = "{\"units\": {\"public/Acme/\": {\"id\": \"a\"}, \"public/Acme/Risk/\": {\"id\": \"r\"}, "
                + "\"public/Beta/\": {\"id\": \"b\"}}}";
        AtomicReference<String> written = new AtomicReference<>();
        ResourceService resources = writableResources(tree, written);
        RuleService rules = mock(RuleService.class);
        OrgUnitService service = new OrgUnitService(resources, rules, locks(), new OrgUnitSettings(true), () -> "id", () -> 0L);

        assertEquals(List.of("public/Acme/Risk", "public/Acme"), service.delete(List.of("public", "Acme"), true));

        List<String> left = new ArrayList<>();
        ProxyUtil.MAPPER.readTree(written.get()).get("units").fieldNames().forEachRemaining(left::add);
        assertEquals(List.of("public/Beta/"), left);
        verify(rules).removeRulesUnder(argThat(folder -> folder.getUrl().equals("files/public/Acme/")));
    }

    @Test
    void testDisplayNameUpdateKeepsWhatThisNodeDoesNotKnow() throws Exception {
        String stored = "{\"units\": {\"public/Acme/\": {\"id\": \"a\", \"displayName\": \"Acme\", \"future\": {\"x\": 1}},"
                + " \"public/Beta/\": {\"id\": \"b\", \"later\": true}}, \"version\": 3}";
        AtomicReference<String> written = new AtomicReference<>();
        OrgUnitService service = writable(stored, written);

        service.updateDisplayName(List.of("public", "Acme"), "Acme Corp");

        JsonNode document = ProxyUtil.MAPPER.readTree(written.get());
        assertEquals("Acme Corp", document.at("/units/public~1Acme~1/displayName").textValue());
        assertEquals(1, document.at("/units/public~1Acme~1/future/x").intValue());
        assertEquals("a", document.at("/units/public~1Acme~1/id").textValue());
        assertTrue(document.at("/units/public~1Beta~1/later").booleanValue());
        assertEquals(3, document.get("version").intValue());

        service.updateDisplayName(List.of("public", "Acme"), " ");
        assertEquals("Acme", ProxyUtil.MAPPER.readTree(written.get()).at("/units/public~1Acme~1/displayName").textValue());
    }

    @Test
    void testDisplayNameUpdateRepairsAnUnreadableEntry() throws Exception {
        AtomicReference<String> written = new AtomicReference<>();
        OrgUnitService service = writable("{\"units\": {\"public/Acme/\": \"x\"}}", written);

        service.updateDisplayName(List.of("public", "Acme"), "Acme Corp");

        assertEquals("Acme Corp", ProxyUtil.MAPPER.readTree(written.get()).at("/units/public~1Acme~1/displayName").textValue());
    }

    @Test
    void testDisplayNameUpdateRefusals() {
        AtomicReference<String> written = new AtomicReference<>();
        OrgUnitService service = writable("{\"units\": {\"public/Acme/\": {\"id\": \"a\"}}}", written);

        assertThrows(IllegalArgumentException.class, () -> service.updateDisplayName(List.of("public"), "Shared"));
        assertEquals("displayName is required",
                assertThrows(IllegalArgumentException.class, () -> service.updateDisplayName(List.of("public", "Acme"), null)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> service.updateDisplayName(List.of("public", "Acme"), "x".repeat(129)));
        assertThrows(IllegalArgumentException.class, () -> service.updateDisplayName(List.of("public", "Acme"), "line\nbreak"));
        assertThrows(ResourceNotFoundException.class, () -> service.updateDisplayName(List.of("public", "Beta"), "Beta"));
        assertNull(written.get());
    }

    @Test
    void testAssignmentsComeFromTheUnitsPolicy() {
        FakeOrgUnitStorage storage = new FakeOrgUnitStorage(Map.of("units", UNITS, "b", """
                {"roleAssignments": [{"id": "a1", "roleName": "approver",
                  "subject": {"type": "claimsPredicate", "source": "groups", "function": "EQUAL", "targets": ["basel-leads"]}}]}
                """));
        OrgUnitService service = service(storage);
        List<String> basel = List.of("public", "Risk", "US Risk", "Basel");

        assertEquals(List.of(new RoleAssignment("a1", "approver", new Subject.ClaimsPredicate("groups", Rule.Function.EQUAL, List.of("basel-leads")))),
                service.listRoleAssignments(basel));
        assertEquals(List.of(), service.listRoleAssignments(List.of("public", "Risk")));
    }

    @Test
    void testLegacyAssignmentsInTheTreeAreIgnored() {
        String tree = """
                {"units": {"public/Acme/": {"id": "a", "roleAssignments": [{"id": "r1", "roleName": "admin",
                  "subject": {"type": "claimsPredicate", "source": "email", "function": "EQUAL", "targets": ["bob@acme.com"]}}]}}}
                """;
        OrgUnitService service = service(new FakeOrgUnitStorage(Map.of("units", tree)));
        assertEquals(List.of(), service.listRoleAssignments(List.of("public", "Acme")));
    }

    @Test
    void testRoleAssignmentWritesTouchOnlyTheUnitsPolicy() throws Exception {
        String tree = "{\"units\": {\"public/Acme/\": {\"id\": \"a\", \"displayName\": \"Acme\"}, \"public/Beta/\": {\"id\": \"b\"}}}";
        String policy = """
                {"version": 3, "roleAssignments": [
                  {"id": "r1", "roleName": "approver", "subject": {"type": "group", "name": "leads"}},
                  {"id": "r2", "roleName": "admin", "subject": {"type": "claimsPredicate", "source": "email", "function": "EQUAL", "targets": ["bob@acme.com"]}}]}
                """;
        String beta = "{\"roleAssignments\": []}";
        FakeOrgUnitStorage storage = new FakeOrgUnitStorage(Map.of("units", tree, "a", policy, "b", beta));
        OrgUnitService service = service(storage);
        Subject leads = new Subject.ClaimsPredicate("groups", Rule.Function.EQUAL, List.of("acme-leads"));

        RoleAssignment added = service.addRoleAssignment(List.of("public", "Acme"), "editor", leads, ROLES);

        assertEquals(new RoleAssignment("id", "editor", leads), added);
        assertEquals(tree, storage.documents.get("units"));
        assertEquals(beta, storage.documents.get("b"));
        JsonNode stored = ProxyUtil.MAPPER.readTree(storage.documents.get("a"));
        JsonNode before = ProxyUtil.MAPPER.readTree(policy);
        assertEquals(3, stored.get("version").intValue());
        assertEquals(before.get("roleAssignments").get(0), stored.get("roleAssignments").get(0));
        assertEquals(before.get("roleAssignments").get(1), stored.get("roleAssignments").get(1));
        assertEquals(ProxyUtil.MAPPER.valueToTree(added), stored.get("roleAssignments").get(2));

        List<String> asked = new ArrayList<>();
        HttpException refused = assertThrows(HttpException.class, () -> service.removeRoleAssignment(List.of("public", "Acme"), "r2", roleName -> {
            asked.add(roleName);
            return false;
        }));
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatus());
        assertEquals(List.of("admin"), asked);
        assertEquals(3, ProxyUtil.MAPPER.readTree(storage.documents.get("a")).get("roleAssignments").size());

        service.removeRoleAssignment(List.of("public", "Acme"), "r1", roleName -> true);
        stored = ProxyUtil.MAPPER.readTree(storage.documents.get("a"));
        assertEquals(List.of("r2", "id"), List.of(stored.get("roleAssignments").get(0).get("id").textValue(),
                stored.get("roleAssignments").get(1).get("id").textValue()));
        assertThrows(ResourceNotFoundException.class, () -> service.removeRoleAssignment(List.of("public", "Acme"), "r9", roleName -> true));

        service.addRoleAssignment(List.of("public", "Beta"), "admin", leads, ROLES);
        assertEquals(1, ProxyUtil.MAPPER.readTree(storage.documents.get("b")).get("roleAssignments").size());
        assertEquals(tree, storage.documents.get("units"));
    }

    @Test
    void testFirstAssignmentCreatesThePolicy() {
        FakeOrgUnitStorage storage = new FakeOrgUnitStorage(Map.of("units", "{\"units\": {\"public/Acme/\": {\"id\": \"a\"}}}"));
        OrgUnitService service = service(storage);
        Subject bob = new Subject.ClaimsPredicate("email", Rule.Function.EQUAL, List.of("bob@acme.com"));

        assertEquals(List.of(), service.listRoleAssignments(List.of("public", "Acme")));
        service.addRoleAssignment(List.of("public", "Acme"), "admin", bob, ROLES);
        assertEquals(List.of("admin"), service.listRoleAssignments(List.of("public", "Acme")).stream().map(RoleAssignment::roleName).toList());
    }

    @Test
    void testSameRoleTwiceWritesNothing() throws Exception {
        String policy = """
                {"roleAssignments": [
                  {"id": "r2", "roleName": "admin", "subject": {"type": "claimsPredicate", "source": "email", "function": "EQUAL", "targets": ["bob@acme.com"]}}]}
                """;
        FakeOrgUnitStorage storage = new FakeOrgUnitStorage(Map.of("units", "{\"units\": {\"public/Acme/\": {\"id\": \"a\"}}}", "a", policy));
        OrgUnitService service = service(storage);
        Subject bob = new Subject.ClaimsPredicate("email", Rule.Function.EQUAL, List.of("bob@acme.com"));

        assertEquals("r2", service.addRoleAssignment(List.of("public", "Acme"), "admin", bob, ROLES).id());
        assertEquals(policy, storage.documents.get("a"));

        assertEquals("id", service.addRoleAssignment(List.of("public", "Acme"), "editor", bob, ROLES).id());
        assertEquals(2, ProxyUtil.MAPPER.readTree(storage.documents.get("a")).get("roleAssignments").size());
    }

    @Test
    void testCorruptPolicyGrantsNothingInItsUnitOnly() {
        String tree = "{\"units\": {\"public/Acme/\": {\"id\": \"a\"}, \"public/Beta/\": {\"id\": \"b\"}}}";
        String good = "{\"roleAssignments\": [{\"id\": \"r1\", \"roleName\": \"admin\", "
                + "\"subject\": {\"type\": \"claimsPredicate\", \"source\": \"email\", \"function\": \"EQUAL\", \"targets\": [\"bob@acme.com\"]}}]}";
        Subject bob = new Subject.ClaimsPredicate("email", Rule.Function.EQUAL, List.of("bob@acme.com"));
        for (String corrupt : List.of("{not json", "[]", "\"text\"", "{\"roleAssignments\": {}}", "{}{}")) {
            FakeOrgUnitStorage storage = new FakeOrgUnitStorage(Map.of("units", tree, "a", corrupt, "b", good));
            OrgUnitService service = service(storage);
            OrgUnitList list = service.getUnitList();

            assertThrows(IllegalStateException.class, () -> service.listRoleAssignments(List.of("public", "Acme")), corrupt);
            assertThrows(IllegalStateException.class, () -> service.addRoleAssignment(List.of("public", "Acme"), "admin", bob, ROLES), corrupt);
            assertEquals(corrupt, storage.documents.get("a"));
            assertEquals(List.of(), service.roleAssignmentsOf("public/Acme/", list.units().get("public/Acme/")), corrupt);
            assertEquals(1, service.roleAssignmentsOf("public/Beta/", list.units().get("public/Beta/")).size(), corrupt);
            Map<String, List<RoleAssignment>> all = service.roleAssignmentsOfAll(list.units());
            assertEquals(Set.of("public/Beta/"), all.keySet(), corrupt);
        }
    }

    @Test
    void testAllPoliciesAreReadInOneBatch() {
        String tree = "{\"units\": {\"public/\": {\"id\": \"legacy\"}, \"public/Acme/\": {\"id\": \"a\"}, "
                + "\"public/Beta/\": {\"id\": \"b\"}, \"public/Gamma/\": {\"id\": \"g\"}}}";
        FakeOrgUnitStorage storage = new FakeOrgUnitStorage(Map.of("units", tree, "legacy", "{\"roleAssignments\": []}",
                "a", "{\"roleAssignments\": []}", "b", "{\"roleAssignments\": []}"));
        OrgUnitService service = service(storage);
        OrgUnitList list = service.getUnitList();
        clearInvocations(storage.resources);

        assertEquals(Set.of("public/Acme/", "public/Beta/"), service.roleAssignmentsOfAll(list.units()).keySet());
        verify(storage.resources, times(1)).load(anyList(), anyList());
        verify(storage.resources, never()).getResourceWithMetadata(any(), any());
    }

    @Test
    void testDeleteRemovesTreeEntriesThenPolicies() {
        String tree = "{\"units\": {\"public/Acme/\": {\"id\": \"a\"}, \"public/Acme/Risk/\": {\"id\": \"r\"}, "
                + "\"public/Beta/\": {\"id\": \"b\"}}}";
        FakeOrgUnitStorage storage = new FakeOrgUnitStorage(Map.of("units", tree, "a", "{}", "r", "{}", "b", "{}"));
        OrgUnitService service = service(storage);

        service.delete(List.of("public", "Acme"), true);

        assertEquals(Set.of("units", "b"), storage.documents.keySet());
        InOrder order = inOrder(storage.resources);
        order.verify(storage.resources).computeResource(argThat(descriptor -> descriptor.getName().equals("units")), any(Function.class));
        order.verify(storage.resources, times(2)).deleteResource(any(), any());
    }

    @Test
    void testUnusableIdsHoldNoAssignments() {
        String tree = "{\"units\": {\"public/Acme/\": {\"id\": \"units\"}, \"public/Beta/\": {\"id\": \"../x\"}, "
                + "\"public/Gamma/\": {\"displayName\": \"no id\"}}}";
        FakeOrgUnitStorage storage = new FakeOrgUnitStorage(Map.of("units", tree));
        OrgUnitService service = service(storage);
        Subject bob = new Subject.ClaimsPredicate("email", Rule.Function.EQUAL, List.of("bob@acme.com"));
        OrgUnitList list = service.getUnitList();

        for (String unit : List.of("Acme", "Beta", "Gamma")) {
            assertThrows(IllegalStateException.class, () -> service.listRoleAssignments(List.of("public", unit)), unit);
            assertThrows(IllegalStateException.class, () -> service.addRoleAssignment(List.of("public", unit), "admin", bob, ROLES), unit);
            assertEquals(List.of(), service.roleAssignmentsOf("public/" + unit + "/", list.units().get("public/" + unit + "/")), unit);
        }
        assertEquals(Set.of("units"), storage.documents.keySet());
        assertEquals(tree, storage.documents.get("units"));
        verify(storage.resources, never()).computeResource(any(), any(Function.class));
    }

    @Test
    void testRoleAssignmentServiceRefusals() {
        AtomicReference<String> written = new AtomicReference<>();
        OrgUnitService service = writable("{\"units\": {\"public/Acme/\": {\"id\": \"a\"}}}", written);
        List<String> acme = List.of("public", "Acme");
        Subject jane = new Subject.ClaimsPredicate("email", Rule.Function.EQUAL, List.of("jane@acme.com"));

        assertThrows(IllegalArgumentException.class, () -> service.addRoleAssignment(acme, "owner", jane, ROLES));
        assertThrows(IllegalArgumentException.class, () -> service.addRoleAssignment(acme, null, jane, ROLES));
        assertThrows(IllegalArgumentException.class, () -> service.addRoleAssignment(acme, "admin", null, ROLES));
        assertThrows(IllegalArgumentException.class, () -> service.addRoleAssignment(acme, "admin", new Subject.User("jane"), ROLES));
        assertThrows(IllegalArgumentException.class, () -> service.addRoleAssignment(acme, "admin", new Subject.Any(), ROLES));
        assertThrows(IllegalArgumentException.class, () -> service.addRoleAssignment(acme, "admin",
                new Subject.ClaimsPredicate("groups", Rule.Function.EQUAL, List.of()), ROLES));
        assertThrows(IllegalArgumentException.class, () -> service.addRoleAssignment(acme, "admin",
                new Subject.ClaimsPredicate("groups", Rule.Function.EQUAL, null), ROLES));
        assertThrows(IllegalArgumentException.class, () -> service.addRoleAssignment(acme, "admin",
                new Subject.ClaimsPredicate("$[?(@.email =~ /^((.+)+)+X$/)]", Rule.Function.EQUAL, List.of("x")), ROLES));
        assertThrows(IllegalArgumentException.class, () -> service.addRoleAssignment(acme, "admin",
                new Subject.ClaimsPredicate("a b", Rule.Function.EQUAL, List.of("jane@acme.com")), ROLES));
        assertThrows(IllegalArgumentException.class, () -> service.addRoleAssignment(acme, "admin",
                new Subject.ClaimsPredicate("groups", Rule.Function.EQUAL, List.of("ok", "x".repeat(257))), ROLES));
        assertThrows(IllegalArgumentException.class, () -> service.addRoleAssignment(acme, "admin",
                new Subject.ClaimsPredicate("email", Rule.Function.EQUAL, List.of("jane\n@acme.com")), ROLES));
        assertThrows(IllegalArgumentException.class, () -> service.addRoleAssignment(List.of("public"), "admin", jane, ROLES));
        assertThrows(IllegalArgumentException.class, () -> service.listRoleAssignments(List.of("public")));
        assertThrows(ResourceNotFoundException.class, () -> service.addRoleAssignment(List.of("public", "Risk"), "admin", jane, ROLES));
        assertThrows(ResourceNotFoundException.class, () -> service.listRoleAssignments(List.of("public", "Risk")));
        assertEquals(null, written.get());
    }

    @Test
    void testUnreadableAssignmentGrantsNothingButTheUnitStays() {
        FakeOrgUnitStorage storage = new FakeOrgUnitStorage(Map.of("units", "{\"units\": {\"public/Acme/\": {\"id\": \"a\"}}}", "a", """
                {"roleAssignments": [
                  {"id": "r1", "roleName": "approver", "subject": {"type": "group", "name": "leads"}},
                  {"id": "r2", "roleName": "admin", "expiresAt": "2027-01-01",
                    "subject": {"type": "user", "id": "carol@acme.com"}},
                  null, "text", {}, {"id": "r4", "roleName": "admin"},
                  {"id": "r3", "roleName": "admin", "subject": {"type": "user", "id": "bob@acme.com"}}]}
                """));
        OrgUnitService service = service(storage);

        assertEquals(List.of("r3"), service.listRoleAssignments(List.of("public", "Acme")).stream().map(RoleAssignment::id).toList());
        assertEquals("public/Acme/", OrgUnitService.ownerOf(service.getUnitList().units(), resource(ResourceTypes.FILE, "Acme/a.pdf")));
    }

    private static ResourceDescriptor resource(ResourceTypes type, String path) {
        return ResourceDescriptorFactory.fromDecoded(type, ResourceDescriptor.PUBLIC_BUCKET, ResourceDescriptor.PUBLIC_LOCATION, path);
    }

    private static OrgUnitService writable(String stored, AtomicReference<String> written) {
        RuleService rules = mock(RuleService.class);
        return new OrgUnitService(writableResources(stored, written), rules, locks(), new OrgUnitSettings(true), () -> "id", () -> 0L);
    }

    private static ResourceService writableResources(String stored, AtomicReference<String> written) {
        ResourceService resources = mock(ResourceService.class);
        ResourceItemMetadata metadata = metadata("\"1\"");
        when(resources.getResourceWithMetadata(any(), any())).thenReturn(Pair.of(metadata, stored));
        when(resources.computeResource(any(), any(Function.class))).thenAnswer(invocation -> {
            Function<String, String> change = invocation.getArgument(1);
            written.set(change.apply(stored));
            return null;
        });
        return resources;
    }

    @Test
    void testMissingListHasNoUnits() {
        OrgUnitService service = service((String) null);
        assertEquals(0, service.getUnitList().units().size());
    }

    /**
     * Runs what it guards right away, as a single node would.
     */
    @SuppressWarnings("unchecked")
    private static LockService locks() {
        LockService locks = mock(LockService.class);
        when(locks.underBucketLock(any(), any())).thenAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(1)).get());
        return locks;
    }

    private static OrgUnitService service(FakeOrgUnitStorage storage) {
        return new OrgUnitService(storage.resources, mock(RuleService.class), locks(), new OrgUnitSettings(true), () -> "id", () -> 0L);
    }

    private static OrgUnitService service(String document) {
        ResourceService resources = mock(ResourceService.class);
        if (document != null) {
            ResourceItemMetadata metadata = metadata("\"1\"");
            when(resources.getResourceWithMetadata(any(), any())).thenReturn(Pair.of(metadata, document));
        }
        return new OrgUnitService(resources, mock(RuleService.class), locks(), new OrgUnitSettings(true), () -> "id", () -> 0L);
    }

    /**
     * Every write in the same millisecond: only the etag tells them apart.
     */
    private static ResourceItemMetadata metadata(String etag) {
        ResourceItemMetadata metadata = mock(ResourceItemMetadata.class);
        when(metadata.getUpdatedAt()).thenReturn(1L);
        when(metadata.getEtag()).thenReturn(etag);
        return metadata;
    }

}
