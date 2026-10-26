package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.data.orgunit.OrgUnit;
import com.epam.aidial.core.server.data.orgunit.OrgUnitData;
import com.epam.aidial.core.server.data.orgunit.OrgUnitList;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrgUnitServiceTest {

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
        // empty when checked under the lock, published into by a write that doesn't take it before the marker
        when(resources.getFolderMetadata(eq(folder), any(), anyInt(), anyBoolean()))
                .thenReturn(null, new ResourceFolderMetadata(folder, List.of(new ResourceItemMetadata(published)), null));
        RuleService rules = mock(RuleService.class);
        OrgUnitService service = new OrgUnitService(resources, rules, locks(), new OrgUnitSettings(true), () -> "id", () -> 0L);

        service.create(List.of("public"), "Acme", null, "admin");

        verify(resources, never()).putFile(any(), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testOnlyTheExactNameIsLookedUpUnderTheLock() {
        AtomicReference<String> written = new AtomicReference<>();
        ResourceService resources = writableResources("{\"units\": {}}", written);
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
        AtomicBoolean scannedUnderLock = new AtomicBoolean();
        when(resources.getFolderMetadata(argThat(folder -> folder != null && folder.isRootFolder()), any(), anyInt(), anyBoolean()))
                .thenAnswer(invocation -> {
                    scannedUnderLock.compareAndSet(false, locked.get());
                    return null;
                });
        ResourceDescriptor app = ResourceDescriptorFactory.fromDecoded(ResourceTypes.APPLICATION, ResourceDescriptor.PUBLIC_BUCKET,
                ResourceDescriptor.PUBLIC_LOCATION, "Acme");
        AtomicBoolean lookedUpUnderLock = new AtomicBoolean();
        when(resources.hasResource(app)).thenAnswer(invocation -> {
            lookedUpUnderLock.set(locked.get());
            return false;
        });
        RuleService rules = mock(RuleService.class);
        OrgUnitService service = new OrgUnitService(resources, rules, locks, new OrgUnitSettings(true), () -> "id", () -> 0L);

        service.create(List.of("public"), "Acme", null, "admin");

        assertFalse(scannedUnderLock.get());
        assertTrue(lookedUpUnderLock.get());
    }

    @Test
    void testNameTakenAfterTheScanIsRefusedUnderTheLock() {
        AtomicReference<String> written = new AtomicReference<>();
        ResourceService resources = writableResources("{\"units\": {}}", written);
        ResourceDescriptor folder = ResourceDescriptorFactory.fromDecoded(ResourceTypes.PROMPT, ResourceDescriptor.PUBLIC_BUCKET,
                ResourceDescriptor.PUBLIC_LOCATION, "Acme/");
        ResourceDescriptor memo = ResourceDescriptorFactory.fromDecoded(ResourceTypes.PROMPT, ResourceDescriptor.PUBLIC_BUCKET,
                ResourceDescriptor.PUBLIC_LOCATION, "Acme/memo");
        when(resources.getFolderMetadata(eq(folder), any(), anyInt(), anyBoolean()))
                .thenReturn(new ResourceFolderMetadata(folder, List.of(new ResourceItemMetadata(memo)), null));
        RuleService rules = mock(RuleService.class);
        OrgUnitService service = new OrgUnitService(resources, rules, locks(), new OrgUnitSettings(true), () -> "id", () -> 0L);

        HttpException refused = assertThrows(HttpException.class, () -> service.create(List.of("public"), "Acme", null, "admin"));
        assertTrue(refused.getMessage().contains("already has a folder named Acme"), refused.getMessage());

        ResourceService items = writableResources("{\"units\": {}}", written);
        ResourceDescriptor app = ResourceDescriptorFactory.fromDecoded(ResourceTypes.APPLICATION, ResourceDescriptor.PUBLIC_BUCKET,
                ResourceDescriptor.PUBLIC_LOCATION, "Acme");
        when(items.hasResource(app)).thenReturn(true);
        OrgUnitService other = new OrgUnitService(items, rules, locks(), new OrgUnitSettings(true), () -> "id", () -> 0L);

        refused = assertThrows(HttpException.class, () -> other.create(List.of("public"), "Acme", null, "admin"));
        assertTrue(refused.getMessage().contains("already has an item named Acme"), refused.getMessage());
        assertNull(written.get());
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
    }

    @Test
    void testDeleteRemovesTheSubtreeFromTheTreeOnly() throws Exception {
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
        verifyNoInteractions(rules);
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
