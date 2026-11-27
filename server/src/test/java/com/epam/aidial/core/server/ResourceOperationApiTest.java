package com.epam.aidial.core.server;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.server.data.InvitationLink;
import com.epam.aidial.core.server.data.folder.FolderResourceMarker;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.service.resource.ComplexResourceService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.data.FileMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagBuilder;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.epam.aidial.core.storage.util.RedisUtil;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.core5.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.Codec;

import java.io.File;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ResourceOperationApiTest extends ResourceBaseTest {

    private static final String FILE_MIME = "application/x-dial-copy-test";
    private static final String SKILL_MANIFEST = "---\nname: Copy Skill\ndescription: File operations\n---\n# Skill\n";

    private BlobStorage originalStorage;

    @BeforeEach
    void installFilesystemRetries() {
        if (File.separatorChar != '\\') {
            return;
        }
        originalStorage = dial.getProxy().getStorage();
        replaceOperationStorage(createOperationStorageSpy(originalStorage));
    }

    @AfterEach
    void restoreOperationStorage() {
        if (originalStorage != null) {
            replaceOperationStorage(originalStorage);
        }
    }

    private static BlobStorage createOperationStorageSpy(BlobStorage original) {
        BlobStorage storage = Mockito.spy(original);
        if (File.separatorChar == '\\') {
            Mockito.doAnswer(invocation -> FileUtil.retryFilesystemCall(() -> callRealStorage(invocation))).when(storage)
                    .store(Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(byte[].class));
            Mockito.doAnswer(invocation -> FileUtil.retryFilesystemCall(() -> callRealStorage(invocation))).when(storage)
                    .copy(Mockito.anyString(), Mockito.anyString(), Mockito.any());
            Mockito.doAnswer(invocation -> FileUtil.retryFilesystemCall(() -> {
                callRealStorage(invocation);
                String path = invocation.getArgument(0);
                if (original.exists(path)) {
                    throw new FileSystemException(path, null, "Filesystem provider left the deleted blob in place");
                }
                return null;
            })).when(storage).delete(Mockito.anyString());
        }
        return storage;
    }

    private void replaceOperationStorage(BlobStorage storage) {
        replaceProxyService("storage", storage);
        replaceResourceBlobStorage(dial.getProxy().getResourceService(), storage);
        replaceStorageField(dial.getProxy().getComplexResourceService(), ComplexResourceService.class, "blobStorage", storage);
    }

    @SneakyThrows
    private static void replaceStorageField(Object owner, Class<?> type, String name, BlobStorage storage) {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, storage);
    }

    @SneakyThrows
    private static Object callRealStorage(InvocationOnMock invocation) {
        return invocation.callRealMethod();
    }

    @Test
    void testComplexFileCopyMoveMatrix() {
        for (String fromGroup : List.of("files", "skills", "projects")) {
            for (String toGroup : List.of("files", "skills", "projects")) {
                if (fromGroup.equals("files") && toGroup.equals("files")) {
                    continue;
                }
                for (boolean move : List.of(false, true)) {
                    String name = fromGroup + "-" + toGroup + "-" + move;
                    String source = createOperationFile(fromGroup, "source-" + name, "copied content");
                    String destination = createOperationFile(toGroup, "destination-" + name, "old content");
                    Response original = readOperationFile(source);
                    verify(original, 200);
                    verify(fileOperation(source, destination, move, true), 200, "");
                    Response copied = readOperationFile(destination);
                    verify(copied, 200, "copied content");
                    assertEquals(FILE_MIME, copied.headers().get("content-type"));
                    assertEquals(original.headers().get("etag"), copied.headers().get("etag"));
                    if (toGroup.equals("files")) {
                        ResourceDescriptor copiedFile = ResourceDescriptorFactory.fromAnyUrl(destination, encryptionService);
                        assertEquals("FILE", dial.getProxy().getStorage().meta(copiedFile.getAbsoluteFilePath())
                                .getUserMetadata().get(ResourceService.RESOURCE_TYPE_ATTRIBUTE));
                    }
                    verify(readOperationFile(source), move ? 404 : 200);
                }
            }
        }
        for (String group : List.of("skills", "projects")) {
            String source = createOperationFile(group, "within-" + group, "within");
            String destination = operationRoot(source) + "/files/copied.txt";
            for (boolean move : List.of(false, true)) {
                verify(fileOperation(source, destination, move, true), 200, "");
                verify(readOperationFile(destination), 200, "within");
                assertEquals(FILE_MIME, readOperationFile(destination).headers().get("content-type"));
                verify(readOperationFile(source), move ? 404 : 200);
            }
        }
    }

    @Test
    void testComplexFileOverwrite() {
        String source = createOperationFile("projects", "overwrite-source", "new");
        String destination = createOperationFile("skills", "overwrite-destination", "old");
        for (String operation : List.of("copy", "move")) {
            verify(send(HttpMethod.POST, "/v1/ops/resource/" + operation, null,
                    new JsonObject().put("sourceUrl", source).put("destinationUrl", destination).encode()), 409);
        }
        for (boolean move : List.of(false, true)) {
            verify(fileOperation(source, destination, move, false), 409);
            verify(readOperationFile(source), 200, "new");
            verify(readOperationFile(destination), 200, "old");
        }
        verify(fileOperation(source, destination, false, true), 200);
        verify(readOperationFile(destination), 200, "new");
        String plain = createOperationFile("files", "overwrite-plain", "old");
        verify(fileOperation(source, plain, true, false), 409);
        verify(fileOperation(source, plain, true, true), 200);
        verify(readOperationFile(plain), 200, "new");
        verify(readOperationFile(source), 404);
    }

    @Test
    void testComplexFileDestinationMustExist() {
        String source = createOperationFile("files", "existing-source", "source");
        for (String group : List.of("skills", "projects")) {
            String root = group + "/" + bucket + "/absent";
            for (boolean move : List.of(false, true)) {
                Response response = fileOperation(source, root + "/files/a.txt", move, false);
                verify(response, 404);
                assertTrue(response.body().contains("PUT /v2/" + root), response.body());
                verify(readOperationFile(source), 200, "source");
            }
            createOperationFile(group, "deleting-" + group, "file");
            ResourceDescriptor resource = ResourceDescriptorFactory.fromAnyUrl(group + "/" + bucket + "/deleting-" + group, encryptionService);
            ResourceDescriptor marker = ComplexResourceService.getMarkerLockDescriptor(resource);
            ResourceService resources = dial.getProxy().getResourceService();
            resources.computeResource(marker, body -> new JsonObject(body).put("state", "deleting").encode());
            verify(fileOperation(source, resource.getUrl() + "/files/a.txt", true, true), 404);
            verify(readOperationFile(source), 200, "source");
        }
    }

    @Test
    void testComplexFileMissingSource() {
        String destination = createOperationFile("projects", "missing-destination", "unchanged");
        for (String source : List.of("skills/" + bucket + "/absent/files/a.txt", "files/" + bucket + "/absent.txt",
                destination.replace("a.txt", "absent.txt"))) {
            for (boolean move : List.of(false, true)) {
                verify(fileOperation(source, destination, move, true), 400);
                verify(readOperationFile(destination), 200, "unchanged");
            }
        }
    }

    @Test
    void testComplexFileInvalidTargets() {
        String file = createOperationFile("projects", "invalid", "unchanged");
        String root = file.substring(0, file.indexOf("/files/"));
        for (String other : List.of(root, "skills/" + bucket + "/whole", root + "/files/", root + "/files/refs/",
                "files/" + bucket + "/folder/", file, file.replace("a.txt", "%61.txt"))) {
            for (boolean move : List.of(false, true)) {
                verify(fileOperation(file, other, move, true), 400);
                verify(fileOperation(other, file, move, true), 400);
            }
        }
        verify(readOperationFile(file), 200, "unchanged");
    }

    @Test
    void testComplexFileHiddenPaths() {
        String file = createOperationFile("projects", "hidden", "unchanged");
        String prefix = file.substring(0, file.lastIndexOf('/') + 1);
        for (String path : List.of(".dial-resource", ".dial-folder/x", "v/x", "refs/.dial-resource/x", "refs/%76/x",
                "refs/%2Edial-folder/x")) {
            for (boolean move : List.of(false, true)) {
                verify(fileOperation(file, prefix + path, move, false), 404);
                verify(fileOperation(prefix + path, file, move, true), 404);
            }
        }
        for (String path : List.of("refs%2Fx", "refs%5Cx", "../x", "%2e%2e/x")) {
            verify(fileOperation(file, prefix + path, false, false), 400);
        }
        verify(readOperationFile(file), 200, "unchanged");
    }

    @Test
    void testComplexFileMovePermissions() {
        String source = createOperationFile("projects", "shared-source", "source");
        String destination = createOperationFile("projects", "shared-destination", "old");
        String sourceRoot = operationRoot(source);
        String destinationRoot = operationRoot(destination);
        acceptOperationShare(sourceRoot, "READ", "WRITE");
        acceptOperationShare(destinationRoot, "WRITE");
        verify(fileOperation(sourceRoot, destinationRoot, true, true, "api-key", "proxyKey2"), 403);
        verify(fileOperation(source, destination, true, true, "api-key", "proxyKey2"), 200);
        verify(readOperationFile(source), 404);
        verify(readOperationFile(destination), 200, "source");
        String readOnlySource = createOperationFile("projects", "read-only-source", "read only");
        acceptOperationShare(operationRoot(readOnlySource), "READ");
        verify(fileOperation(readOnlySource, destination, false, true, "api-key", "proxyKey2"), 200);
        verify(fileOperation(readOnlySource, destination, true, true, "api-key", "proxyKey2"), 403);
        String sharedPlain = createOperationFile("files", "rw-plain-source", "plain");
        acceptOperationShare(sharedPlain, "READ", "WRITE");
        verify(fileOperation(sharedPlain, destination, true, true, "api-key", "proxyKey2"), 200);
        verify(readOperationFile(sharedPlain), 404);
    }

    @Test
    void testComplexFileOperationAccessBeforeExistence() {
        String source = createOperationFile("projects", "allowed-source", "source");
        acceptOperationShare(operationRoot(source), "READ", "WRITE");
        String destination = createOperationFile("skills", "denied-destination", "old");
        ComplexResourceService original = dial.getProxy().getComplexResourceService();
        ComplexResourceService spy = Mockito.spy(original);
        replaceProxyService("complexResourceService", spy);
        try {
            for (String target : List.of(destination, "skills/" + bucket + "/absent/files/a.txt", "skills/" + bucket + "/absent/files/v/x")) {
                for (boolean move : List.of(false, true)) {
                    Response denied = fileOperation(source, target, move, false, "api-key", "proxyKey2");
                    verify(denied, 403);
                    assertEquals("You don't have an access to: " + operationRoot(target), denied.body());
                }
            }
            Mockito.verify(spy, Mockito.never()).getFileSnapshotLocked(Mockito.any(), Mockito.any());
            Mockito.verify(spy, Mockito.never()).importFileLocked(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                    Mockito.any(), Mockito.anyBoolean(), Mockito.any());
        } finally {
            replaceProxyService("complexResourceService", original);
        }
        verify(readOperationFile(source), 200, "source");
        verify(readOperationFile(destination), 200, "old");
    }

    @Test
    void testComplexFileMutationValidation() {
        String source = createOperationFile("skills", "manifest-source", "source");
        String destination = createOperationFile("projects", "manifest-destination", "old");
        String manifest = operationRoot(source) + "/files/SKILL.md";
        verify(fileOperation(manifest, destination, true, true), 400);
        verify(readOperationFile(manifest), 200, SKILL_MANIFEST);
        verify(readOperationFile(destination), 200, "old");
        String invalid = createOperationFile("files", "invalid-manifest", "not a manifest");
        verify(fileOperation(invalid, manifest, false, true), 400);
        verify(readOperationFile(manifest), 200, SKILL_MANIFEST);
        verify(readOperationFile(invalid), 200, "not a manifest");
        String replacement = createOperationFile("files", "valid-manifest", SKILL_MANIFEST.replace("Copy Skill", "New Name"));
        verify(fileOperation(replacement, manifest, false, true), 200);
        Response metadata = send(HttpMethod.GET, "/v2/metadata/" + operationRoot(source), null, null);
        verify(metadata, 200);
        assertTrue(metadata.body().contains("New Name"), metadata.body());
    }

    @Test
    void testComplexFileCopyMoveLimits() {
        String source = createOperationFile("files", "limit-source", "123456");
        String destination = createOperationFile("projects", "limit-destination", "old");
        ComplexResourceService engine = dial.getProxy().getComplexResourceService();
        ComplexResourceService.Settings settings = readEngineSettings(engine);
        settings.setTypes(Map.of());
        settings.setMaxFiles(2);
        verify(fileOperation(source, operationRoot(destination) + "/files/extra.txt", false, false), 400);
        verify(fileOperation(destination, operationRoot(destination) + "/files/renamed.txt", true, false), 200);
        verify(readOperationFile(operationRoot(destination) + "/files/renamed.txt"), 200, "old");
        settings.setMaxFiles(10);
        settings.setMaxFileSizeBytes(5);
        verify(fileOperation(source, operationRoot(destination) + "/files/extra.txt", false, false), 413);
        settings.setMaxFileSizeBytes(100);
        settings.setMaxTotalBytes(6);
        verify(fileOperation(source, operationRoot(destination) + "/files/extra.txt", false, false), 413);
        verify(readOperationFile(source), 200, "123456");
        verify(readOperationFile(operationRoot(destination) + "/files/extra.txt"), 404);
    }

    @Test
    void testComplexFileCopyRejectsStaleReplicaSize() {
        String source = createOperationFile("files", "replica-size-source", "x");
        String destination = createOperationFile("projects", "replica-size-destination", "x");
        verify(send(HttpMethod.DELETE, "/v1/" + operationRoot(destination) + "/files/keep.txt", null, null), 200);
        ResourceService original = dial.getProxy().getResourceService();
        Map<String, byte[]> replicaFields = captureReplicaFields(original, source);
        verify(putOperationFile(source, "four"), 200);
        ComplexResourceService engine = dial.getProxy().getComplexResourceService();
        ComplexResourceService.Settings limits = readEngineSettings(engine);
        limits.setTypes(Map.of());
        limits.setMaxFileSizeBytes(2);
        ResourceService replica = Mockito.spy(original);
        installReplicaView(replica, source, replicaFields);
        replaceProxyService("resourceService", replica);
        replaceEngineResourceService(engine, replica);
        try {
            assertEquals(1, ((FileMetadata) replica.getResourceMetadata(
                    ResourceDescriptorFactory.fromAnyUrl(source, encryptionService))).getContentLength());
            verify(fileOperation(source, destination, false, true), 413);
            verify(readOperationFile(destination), 200, "x");
        } finally {
            replaceEngineResourceService(engine, original);
            replaceProxyService("resourceService", original);
        }
        verify(readOperationFile(source), 200, "four");
    }

    @Test
    void testComplexFileCopyUsesMasterEtagAndBytes() {
        String source = createOperationFile("files", "replica-etag-source", "x");
        String destination = createOperationFile("projects", "replica-etag-destination", "old");
        ResourceService original = dial.getProxy().getResourceService();
        Map<String, byte[]> replicaFields = captureReplicaFields(original, source);
        String current = "current content";
        verify(putOperationFile(source, current), 200);
        ResourceService replica = Mockito.spy(original);
        installReplicaView(replica, source, replicaFields);
        ComplexResourceService engine = dial.getProxy().getComplexResourceService();
        replaceProxyService("resourceService", replica);
        replaceEngineResourceService(engine, replica);
        try {
            verify(fileOperation(source, destination, false, true), 200);
            Response copied = readOperationFile(destination);
            verify(copied, 200, current);
            assertEquals(EtagBuilder.generateEtag(current.getBytes(StandardCharsets.UTF_8)), copied.headers().get("etag"));
        } finally {
            replaceEngineResourceService(engine, original);
            replaceProxyService("resourceService", original);
        }
    }

    @Test
    void testComplexFileManifestCopyRejectsStaleReplicaBody() {
        String source = createOperationFile("files", "replica-manifest-source", SKILL_MANIFEST);
        String destination = createOperationFile("skills", "replica-manifest-destination", "old");
        ResourceService original = dial.getProxy().getResourceService();
        Map<String, byte[]> replicaFields = captureReplicaFields(original, source);
        verify(putOperationFile(source, "invalid current manifest"), 200);
        ResourceService replica = Mockito.spy(original);
        installReplicaView(replica, source, replicaFields);
        ComplexResourceService engine = dial.getProxy().getComplexResourceService();
        replaceProxyService("resourceService", replica);
        replaceEngineResourceService(engine, replica);
        try {
            assertEquals(SKILL_MANIFEST, new String(replica.getResourceBytes(ResourceDescriptorFactory.fromAnyUrl(source, encryptionService)),
                    StandardCharsets.UTF_8));
            String manifest = operationRoot(destination) + "/files/SKILL.md";
            verify(fileOperation(source, manifest, false, true), 400);
            verify(readOperationFile(manifest), 200, SKILL_MANIFEST);
        } finally {
            replaceEngineResourceService(engine, original);
            replaceProxyService("resourceService", original);
        }
        verify(readOperationFile(source), 200, "invalid current manifest");
    }

    @Test
    @Timeout(30)
    void testSameResourceFileMoveIsAtomic() throws Exception {
        String source = createOperationFile("projects", "atomic", "source");
        String root = operationRoot(source);
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl(root, encryptionService);
        String before = dial.getProxy().getComplexResourceService().getMarker(descriptor).getCurrentVersion();
        String fileEtag = readOperationFile(source).headers().get("etag");
        try (EventStream stream = subscribe(new JsonObject().put("resources",
                new JsonArray().add(new JsonObject().put("url", root))).encode())) {
            CompletableFuture<Response> move = CompletableFuture.supplyAsync(() -> fileOperation(source, root + "/files/renamed.txt", true, false));
            try {
                for (int i = 0; i < 10; i++) {
                    Response listing = send(HttpMethod.GET, "/v1/metadata/" + root + "/files/", "recursive=true", null);
                    verify(listing, 200);
                    JsonArray items = new JsonObject(listing.body()).getJsonArray("items");
                    List<String> urls = items.stream().map(item -> ((JsonObject) item).getString("url")).toList();
                    assertEquals(2, items.size(), listing.body());
                    assertTrue(urls.contains(source) ^ urls.contains(root + "/files/renamed.txt"), listing.body());
                    Set<String> resourceEtags = new HashSet<>();
                    for (Object value : items) {
                        JsonObject item = (JsonObject) value;
                        JsonObject resource = item.getJsonObject("resource");
                        assertNotNull(resource, listing.body());
                        assertEquals(root, resource.getString("url"));
                        assertNotNull(item.getString("etag"), listing.body());
                        String resourceEtag = resource.getString("etag");
                        assertNotNull(resourceEtag, listing.body());
                        resourceEtags.add(resourceEtag);
                        if (source.equals(item.getString("url")) || (root + "/files/renamed.txt").equals(item.getString("url"))) {
                            assertEquals(fileEtag, item.getString("etag"));
                        }
                    }
                    assertEquals(1, resourceEtags.size(), listing.body());
                }
                verify(move.get(10, TimeUnit.SECONDS), 200);
                List<JsonObject> events = List.of(new JsonObject(stream.take()), new JsonObject(stream.take()), new JsonObject(stream.take()));
                assertEquals(1L, events.stream().filter(event -> root.equals(event.getString("url"))).count(), events.toString());
                assertTrue(stream.events.isEmpty());
            } finally {
                move.get(10, TimeUnit.SECONDS);
            }
        }
        FolderResourceMarker marker = dial.getProxy().getComplexResourceService().getMarker(descriptor);
        assertNotEquals(before, marker.getCurrentVersion());
        assertFalse(marker.getFileMetadata().containsKey("a.txt"));
        assertTrue(marker.getFileMetadata().containsKey("renamed.txt"));
        verify(readOperationFile(source), 404);
        verify(readOperationFile(root + "/files/renamed.txt"), 200, "source");
    }

    @Test
    void testComplexFileMoveDestinationFailure() {
        String source = createOperationFile("projects", "failure-source", "source");
        String destination = createOperationFile("skills", "failure-destination", "old");
        ResourceService original = dial.getProxy().getResourceService();
        ResourceService spy = Mockito.spy(original);
        ComplexResourceService engine = dial.getProxy().getComplexResourceService();
        replaceEngineResourceService(engine, spy);
        Mockito.doThrow(new IllegalStateException("destination provider copy failed"))
                .when(spy).copyResourceLocked(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyBoolean());
        try {
            verify(fileOperation(source, destination, true, true), 500);
            verify(readOperationFile(source), 200, "source");
            verify(readOperationFile(destination), 200, "old");
        } finally {
            replaceEngineResourceService(engine, original);
        }
    }

    @Test
    void testComplexFileMoveSourceDeleteFailure() {
        String source = createOperationFile("projects", "delete-source", "source");
        String destination = createOperationFile("skills", "delete-destination", "old");
        ComplexResourceService original = dial.getProxy().getComplexResourceService();
        ComplexResourceService spy = Mockito.spy(original);
        Mockito.doThrow(new IllegalStateException("source marker commit failed"))
                .when(spy).deleteFileLocked(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        replaceProxyService("complexResourceService", spy);
        try {
            verify(fileOperation(source, destination, true, true), 500);
            verify(readOperationFile(source), 200, "source");
            verify(readOperationFile(destination), 200, "source");
        } finally {
            replaceProxyService("complexResourceService", original);
        }
        verify(fileOperation(source, destination, true, true), 200);
        verify(readOperationFile(source), 404);

        String plain = createOperationFile("files", "delete-plain", "plain");
        InvitationLink invitation = acceptOperationShare(plain, "READ");
        ResourceService resources = dial.getProxy().getResourceService();
        ResourceService failing = Mockito.spy(resources);
        BlobStorage failingStorage = createOperationStorageSpy(originalStorage == null ? dial.getProxy().getStorage() : originalStorage);
        Mockito.doThrow(new IllegalStateException("plain source provider delete failed")).when(failingStorage)
                .delete(ResourceDescriptorFactory.fromAnyUrl(plain, encryptionService).getAbsoluteFilePath());
        replaceResourceBlobStorage(failing, failingStorage);
        replaceProxyService("resourceService", failing);
        try {
            verify(fileOperation(plain, destination, true, true), 500);
            verify(readOperationFile(plain, "api-key", "proxyKey2"), 200, "plain");
            assertTrue(send(HttpMethod.GET, invitation.invitationLink(), null, null).body().contains(plain));
            verify(readOperationFile(destination), 200, "plain");
        } finally {
            replaceProxyService("resourceService", resources);
        }
        verify(fileOperation(plain, destination, true, true), 200);
        verify(readOperationFile(plain, "api-key", "proxyKey2"), 403);
        assertFalse(send(HttpMethod.GET, invitation.invitationLink(), null, null).body().contains(plain));
    }

    @Test
    void testComplexFileMoveAppliedMarkerFailureRestoresSource() {
        for (String sourceGroup : List.of("skills", "projects")) {
            for (String destinationGroup : List.of("projects", "files")) {
                String suffix = sourceGroup + "-" + destinationGroup;
                String source = createOperationFile(sourceGroup, "applied-source-" + suffix, "moved content");
                String destination = createOperationFile(destinationGroup, "applied-destination-" + suffix, "old");
                String sourceRoot = operationRoot(source);
                String keep = sourceRoot + "/files/keep.txt";
                Response originalMetadata = send(HttpMethod.GET, "/v1/metadata/" + source, null, null);
                verify(originalMetadata, 200);
                InvitationLink sourceInvitation = acceptOperationShare(sourceRoot, "READ");
                String sourceInvitationBefore = send(HttpMethod.GET, sourceInvitation.invitationLink(), null, null).body();
                InvitationLink destinationInvitation = destinationGroup.equals("files") ? null : acceptOperationShare(operationRoot(destination), "READ");
                String destinationInvitationBefore = destinationInvitation == null ? null
                        : send(HttpMethod.GET, destinationInvitation.invitationLink(), null, null).body();
                ResourceDescriptor root = ResourceDescriptorFactory.fromAnyUrl(sourceRoot, encryptionService);
                ResourceDescriptor marker = ComplexResourceService.getMarkerLockDescriptor(root);
                ResourceService original = dial.getProxy().getResourceService();
                ResourceService failing = Mockito.spy(original);
                ComplexResourceService engine = dial.getProxy().getComplexResourceService();
                AtomicBoolean applied = new AtomicBoolean();
                Mockito.doAnswer(invocation -> {
                    Object result = invocation.callRealMethod();
                    JsonObject proposed = new JsonObject((String) invocation.getArgument(1));
                    if (!proposed.getJsonObject("fileMetadata").containsKey("a.txt") && applied.compareAndSet(false, true)) {
                        JsonObject committed = new JsonObject(original.getResourceLocked(marker, EtagHeader.ANY));
                        assertFalse(committed.getJsonObject("fileMetadata").containsKey("a.txt"));
                        assertTrue(committed.getJsonObject("fileMetadata").containsKey("keep.txt"));
                        throw new RedisException("source marker applied but acknowledgement lost");
                    }
                    return result;
                }).when(failing).putResource(Mockito.eq(marker), Mockito.anyString(), Mockito.eq(EtagHeader.ANY), Mockito.any(), Mockito.eq(false));
                replaceProxyService("resourceService", failing);
                replaceEngineResourceService(engine, failing);
                try {
                    verify(fileOperation(source, destination, true, true), 500);
                    assertTrue(applied.get(), "the actual source-removal marker must commit before the injected failure");
                    verify(readOperationFile(source), 200, "moved content");
                    verify(readOperationFile(destination), 200, "moved content");
                    verify(readOperationFile(keep), 200, "keep");
                    verify(readOperationFile(source, "api-key", "proxyKey2"), 200, "moved content");
                    verify(readOperationFile(keep, "api-key", "proxyKey2"), 200, "keep");
                    assertEquals(sourceInvitationBefore, send(HttpMethod.GET, sourceInvitation.invitationLink(), null, null).body());
                    if (destinationInvitation != null) {
                        verify(readOperationFile(destination, "api-key", "proxyKey2"), 200, "moved content");
                        assertEquals(destinationInvitationBefore, send(HttpMethod.GET, destinationInvitation.invitationLink(), null, null).body());
                    } else {
                        verify(readOperationFile(destination, "api-key", "proxyKey2"), 403);
                    }
                    JsonObject saved = new JsonObject(originalMetadata.body());
                    JsonObject restored = new JsonObject(send(HttpMethod.GET, "/v1/metadata/" + source, null, null).body());
                    for (String field : List.of("etag", "contentType", "contentLength", "author", "createdAt", "updatedAt")) {
                        assertEquals(saved.getValue(field), restored.getValue(field), field);
                    }
                } finally {
                    replaceEngineResourceService(engine, original);
                    replaceProxyService("resourceService", original);
                }
                verify(fileOperation(source, destination, true, true), 200);
                verify(readOperationFile(source), 404);
                verify(readOperationFile(keep), 200, "keep");
            }
        }
    }

    @Test
    void testComplexFileMoveRedisFailureRetainsSourceMetadata() {
        for (boolean failSync : List.of(false, true)) {
            String source = createOperationFile("files", "redis-source-" + failSync, "source");
            String destination = createOperationFile("projects", "redis-destination-" + failSync, "old");
            InvitationLink invitation = acceptOperationShare(source, "READ");
            Response before = send(HttpMethod.GET, "/v1/metadata/" + source, null, null);
            verify(before, 200);
            ResourceService original = dial.getProxy().getResourceService();
            ResourceService failing = Mockito.spy(original);
            installOneShotDeleteRedisFailure(failing, source, failSync);
            replaceProxyService("resourceService", failing);
            try {
                verify(fileOperation(source, destination, true, true), 500);
                verify(readOperationFile(source), 200, "source");
                verify(readOperationFile(destination), 200, "source");
                verify(readOperationFile(source, "api-key", "proxyKey2"), 200, "source");
                assertTrue(send(HttpMethod.GET, invitation.invitationLink(), null, null).body().contains(source));
                JsonObject previousMetadata = new JsonObject(before.body());
                JsonObject restoredMetadata = new JsonObject(send(HttpMethod.GET, "/v1/metadata/" + source, null, null).body());
                for (String field : List.of("etag", "author", "createdAt", "updatedAt", "contentType", "contentLength")) {
                    assertEquals(previousMetadata.getValue(field), restoredMetadata.getValue(field), field);
                }
            } finally {
                replaceProxyService("resourceService", original);
            }
            verify(fileOperation(source, destination, true, true), 200);
            verify(readOperationFile(source), 404);
        }
    }

    @Test
    @Timeout(30)
    void testComplexFileMoveConcurrentReplacement() throws Exception {
        String source = createOperationFile("files", "concurrent", "original");
        String destination = createOperationFile("projects", "concurrent-destination", "old");
        ComplexResourceService original = dial.getProxy().getComplexResourceService();
        ComplexResourceService spy = Mockito.spy(original);
        CountDownLatch copying = new CountDownLatch(1);
        CountDownLatch uploading = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        ResourceService originalResources = dial.getProxy().getResourceService();
        ResourceService resourceSpy = Mockito.spy(originalResources);
        Mockito.doAnswer(invocation -> {
            uploading.countDown();
            return invocation.callRealMethod();
        }).when(resourceSpy).putFile(Mockito.eq(ResourceDescriptorFactory.fromAnyUrl(source, encryptionService)),
                Mockito.any(byte[].class), Mockito.any(), Mockito.any(), Mockito.any());
        replaceProxyService("resourceService", resourceSpy);
        Mockito.doAnswer(invocation -> {
            copying.countDown();
            assertTrue(resume.await(10, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(spy).importFileLocked(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyBoolean(), Mockito.any());
        replaceProxyService("complexResourceService", spy);
        CompletableFuture<Response> move = null;
        CompletableFuture<Response> upload = null;
        try {
            move = CompletableFuture.supplyAsync(() -> fileOperation(source, destination, true, true));
            assertTrue(copying.await(10, TimeUnit.SECONDS));
            upload = CompletableFuture.supplyAsync(() -> putOperationFile(source, "replacement"));
            assertTrue(uploading.await(10, TimeUnit.SECONDS));
            assertFalse(upload.isDone(), "ordinary uploads must wait for the plain source record lock");
            resume.countDown();
            verify(move.get(10, TimeUnit.SECONDS), 200);
            verify(upload.get(10, TimeUnit.SECONDS), 200);
            verify(readOperationFile(source), 200, "replacement");
            verify(readOperationFile(destination), 200, "original");
        } finally {
            resume.countDown();
            if (move != null) {
                move.get(10, TimeUnit.SECONDS);
            }
            if (upload != null) {
                upload.get(10, TimeUnit.SECONDS);
            }
            replaceProxyService("resourceService", originalResources);
            replaceProxyService("complexResourceService", original);
        }
    }

    @Test
    @Timeout(30)
    void testReverseFileOperations() throws Exception {
        for (boolean move : List.of(false, true)) {
            String complex = createOperationFile("projects", "reverse-" + move, "complex");
            String plain = createOperationFile("files", "reverse-" + move, "plain");
            CountDownLatch start = new CountDownLatch(1);
            CompletableFuture<Response> first = CompletableFuture.supplyAsync(() -> {
                awaitOperationStart(start);
                return fileOperation(complex, plain, move, true);
            });
            CompletableFuture<Response> second = CompletableFuture.supplyAsync(() -> {
                awaitOperationStart(start);
                return fileOperation(plain, complex, move, true);
            });
            start.countDown();
            verify(first.get(10, TimeUnit.SECONDS), 200);
            verify(second.get(10, TimeUnit.SECONDS), 200);
            Response plainResult = readOperationFile(plain);
            Response complexResult = readOperationFile(complex);
            if (move) {
                assertTrue(plainResult.status() == 200 ^ complexResult.status() == 200);
                assertTrue("plain".equals(plainResult.body()) || "complex".equals(plainResult.body())
                        || "plain".equals(complexResult.body()) || "complex".equals(complexResult.body()));
            } else {
                verify(plainResult, 200);
                verify(complexResult, 200);
                assertEquals(plainResult.body(), complexResult.body());
            }
        }
    }

    @Test
    void testComplexFileRootSharesStayPut() {
        String source = createOperationFile("projects", "root-share-source", "source");
        String destination = createOperationFile("skills", "root-share-destination", "old");
        InvitationLink fromInvitation = acceptOperationShare(operationRoot(source), "READ");
        InvitationLink toInvitation = acceptOperationShare(operationRoot(destination), "READ");
        String beforeSource = send(HttpMethod.GET, fromInvitation.invitationLink(), null, null).body();
        String beforeDestination = send(HttpMethod.GET, toInvitation.invitationLink(), null, null).body();
        Response created = send(HttpMethod.POST, "/v1/ops/publication/create", null, new JsonObject().put("targetFolder", "public/")
                .put("resources", new JsonArray().add(new JsonObject().put("action", "ADD").put("sourceUrl", operationRoot(source))
                        .put("targetUrl", "projects/public/published-file-source"))).encode());
        verify(created, 200);
        String publicationUrl = new JsonObject(created.body()).getString("url");
        String publicationRequest = new JsonObject().put("url", publicationUrl).encode();
        verify(send(HttpMethod.POST, "/v1/ops/publication/approve", null, publicationRequest, "authorization", "admin"), 200);
        String publicationBefore = send(HttpMethod.POST, "/v1/ops/publication/get", null, publicationRequest).body();
        verify(fileOperation(source, destination, true, true), 200);
        verify(fileOperation("projects/public/published-file-source/files/a.txt", destination, false, true), 200);
        assertEquals(publicationBefore, send(HttpMethod.POST, "/v1/ops/publication/get", null, publicationRequest).body());
        assertEquals(beforeSource, send(HttpMethod.GET, fromInvitation.invitationLink(), null, null).body());
        assertEquals(beforeDestination, send(HttpMethod.GET, toInvitation.invitationLink(), null, null).body());
        verify(readOperationFile(destination, "api-key", "proxyKey2"), 200, "source");
        verify(readOperationFile(operationRoot(source) + "/files/keep.txt", "api-key", "proxyKey2"), 200, "keep");
    }

    @Test
    void testPlainFileMoveIntoComplexShareCleanup() {
        String source = createOperationFile("files", "plain-shared", "plain");
        String destination = createOperationFile("projects", "plain-shared-destination", "old");
        InvitationLink invitation = acceptOperationShare(source, "READ");
        verify(fileOperation(source, destination, true, true), 200);
        verify(readOperationFile(source, "api-key", "proxyKey2"), 403);
        verify(readOperationFile(destination, "api-key", "proxyKey2"), 403);
        assertFalse(send(HttpMethod.GET, invitation.invitationLink(), null, null).body().contains(source));
        verify(readOperationFile(destination), 200, "plain");
    }

    @Test
    void testComplexFileOperationEvents() {
        String source = createOperationFile("projects", "event-source", "source");
        String destination = createOperationFile("skills", "event-destination", "old");
        try (EventStream stream = subscribe(new JsonObject().put("resources", new JsonArray()
                .add(new JsonObject().put("url", operationRoot(source))).add(new JsonObject().put("url", operationRoot(destination)))).encode())) {
            verify(fileOperation(source, destination, true, true), 200);
            Set<String> expected = Set.of(operationRoot(source), operationRoot(destination), source, destination);
            List<String> urls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                JsonObject event = new JsonObject(stream.take());
                urls.add(event.getString("url"));
                assertFalse(event.getString("url").contains("/.dial-resource"));
                assertFalse(event.getString("url").contains("/v/"));
            }
            assertEquals(expected, Set.copyOf(urls));
            assertTrue(stream.events.isEmpty());
        }
    }

    private String createOperationFile(String group, String name, String content) {
        String root = group + "/" + bucket + "/" + name;
        if (!group.equals("files")) {
            Map<String, String> files = group.equals("skills") ? Map.of("SKILL.md", SKILL_MANIFEST, "keep.txt", "keep") : Map.of("keep.txt", "keep");
            verify(uploadOperationParts("/v2/" + root, files), 200);
        }
        String url = group.equals("files") ? root + ".txt" : root + "/files/a.txt";
        verify(putOperationFile(url, content), 200);
        return url;
    }

    private Response putOperationFile(String url, String content) {
        return uploadOperationParts("/v1/" + url, Map.of("a.txt", content));
    }

    @SneakyThrows
    private Response uploadOperationParts(String path, Map<String, String> files) {
        HttpUriRequestBase request = new HttpUriRequestBase("PUT", URI.create("http://127.0.0.1:" + serverPort + path));
        request.setHeader("api-key", "proxyKey1");
        MultipartEntityBuilder parts = MultipartEntityBuilder.create();
        files.forEach((name, content) -> parts.addBinaryBody(name, content.getBytes(StandardCharsets.UTF_8), ContentType.create(FILE_MIME), name));
        request.setEntity(parts.build());
        return client.execute(request, ResourceBaseTest::toResponse);
    }

    private Response readOperationFile(String url, String... headers) {
        return send(HttpMethod.GET, (url.startsWith("files/") ? "/v1/" : "/v2/") + url, null, null, headers);
    }

    private Response fileOperation(String source, String destination, boolean move, boolean overwrite, String... headers) {
        return send(HttpMethod.POST, "/v1/ops/resource/" + (move ? "move" : "copy"), null, new JsonObject()
                .put("sourceUrl", source).put("destinationUrl", destination).put("overwrite", overwrite).encode(), headers);
    }

    private InvitationLink acceptOperationShare(String url, String... permissions) {
        Response created = operationRequest("/v1/ops/resource/share/create", new JsonObject().put("invitationType", "link")
                .put("resources", new JsonArray().add(new JsonObject().put("url", url).put("permissions", new JsonArray(List.of(permissions))))).encode());
        verify(created, 200);
        InvitationLink invitation = ProxyUtil.convertToObject(created.body(), InvitationLink.class);
        verify(send(HttpMethod.GET, invitation.invitationLink(), "accept=true", null, "api-key", "proxyKey2"), 200);
        return invitation;
    }

    private static String operationRoot(String url) {
        return url.substring(0, url.indexOf("/files/"));
    }

    @SneakyThrows
    private void replaceProxyService(String name, Object value) {
        Field field = Proxy.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(dial.getProxy(), value);
    }

    private Map<String, byte[]> captureReplicaFields(ResourceService resources, String sourceUrl) {
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl(sourceUrl, encryptionService);
        try (var ignored = resources.lockResource(descriptor)) {
            FileMetadata metadata = (FileMetadata) resources.getResourceMetadataLocked(descriptor);
            Map<String, byte[]> fields = new HashMap<>();
            fields.put("exists", RedisUtil.BOOLEAN_TRUE_ARRAY);
            fields.put("synced", RedisUtil.BOOLEAN_TRUE_ARRAY);
            fields.put("body", resources.getResourceBytesLocked(descriptor, EtagHeader.ANY));
            fields.put(ResourceService.ETAG_ATTRIBUTE, metadata.getEtag().getBytes(StandardCharsets.UTF_8));
            fields.put("content_length", Long.toString(metadata.getContentLength()).getBytes(StandardCharsets.UTF_8));
            fields.put("content_type", metadata.getContentType().getBytes(StandardCharsets.UTF_8));
            fields.put(ResourceService.RESOURCE_TYPE_ATTRIBUTE, descriptor.getType().name().getBytes(StandardCharsets.UTF_8));
            fields.put(ResourceService.CREATED_AT_ATTRIBUTE, metadata.getCreatedAt().toString().getBytes(StandardCharsets.UTF_8));
            fields.put(ResourceService.UPDATED_AT_ATTRIBUTE, metadata.getUpdatedAt().toString().getBytes(StandardCharsets.UTF_8));
            fields.put("author", metadata.getAuthor().getBytes(StandardCharsets.UTF_8));
            return fields;
        }
    }

    @SneakyThrows
    private void installReplicaView(ResourceService resources, String sourceUrl, Map<String, byte[]> replicaFields) {
        Field field = ResourceService.class.getDeclaredField("redis");
        field.setAccessible(true);
        RedissonClient original = (RedissonClient) field.get(resources);
        RedissonClient replica = Mockito.mock(RedissonClient.class, AdditionalAnswers.delegatesTo(original));
        String key = RedisUtil.redisKey(ResourceDescriptorFactory.fromAnyUrl(sourceUrl, encryptionService), resources.getPrefix());
        Mockito.doAnswer(invocation -> {
            Codec codec = invocation.getArgument(1);
            RMap<String, byte[]> map = Mockito.spy(original.<String, byte[]>getMap(key, codec));
            Mockito.doAnswer(read -> {
                Set<String> requested = read.getArgument(0);
                Map<String, byte[]> fields = new HashMap<>();
                requested.forEach(name -> {
                    if (replicaFields.containsKey(name)) {
                        fields.put(name, replicaFields.get(name));
                    }
                });
                return fields;
            }).when(map).getAll(Mockito.anySet());
            return map;
        }).when(replica).getMap(Mockito.eq(key), Mockito.any(Codec.class));
        field.set(resources, replica);
    }

    @SneakyThrows
    private void installOneShotDeleteRedisFailure(ResourceService resources, String sourceUrl, boolean failSync) {
        Field field = ResourceService.class.getDeclaredField("redis");
        field.setAccessible(true);
        RedissonClient original = (RedissonClient) field.get(resources);
        RedissonClient failing = Mockito.mock(RedissonClient.class, AdditionalAnswers.delegatesTo(original));
        String key = RedisUtil.redisKey(ResourceDescriptorFactory.fromAnyUrl(sourceUrl, encryptionService), resources.getPrefix());
        AtomicBoolean failed = new AtomicBoolean();
        Mockito.doAnswer(invocation -> {
            Codec codec = invocation.getArgument(1);
            RMap<String, byte[]> map = Mockito.spy(original.<String, byte[]>getMap(key, codec));
            Answer<Object> failAfterWrite = write -> {
                Object result = write.callRealMethod();
                if (failed.compareAndSet(false, true)) {
                    throw new RedisException("one-shot source deletion Redis failure");
                }
                return result;
            };
            if (failSync) {
                Mockito.doAnswer(failAfterWrite).when(map).put(Mockito.eq("synced"), Mockito.any(byte[].class));
            } else {
                Mockito.doAnswer(failAfterWrite).when(map).putAll(Mockito.anyMap());
            }
            return map;
        }).when(failing).getMap(Mockito.eq(key), Mockito.any(Codec.class));
        field.set(resources, failing);
    }

    @SneakyThrows
    private static void replaceResourceBlobStorage(ResourceService resources, BlobStorage storage) {
        Field field = ResourceService.class.getDeclaredField("blobStore");
        field.setAccessible(true);
        field.set(resources, storage);
    }

    @SneakyThrows
    private static void replaceEngineResourceService(ComplexResourceService engine, ResourceService resources) {
        Field field = ComplexResourceService.class.getDeclaredField("resourceService");
        field.setAccessible(true);
        field.set(engine, resources);
    }

    @SneakyThrows
    private static ComplexResourceService.Settings readEngineSettings(ComplexResourceService engine) {
        Field field = ComplexResourceService.class.getDeclaredField("settings");
        field.setAccessible(true);
        return (ComplexResourceService.Settings) field.get(engine);
    }

    @SneakyThrows
    private static void awaitOperationStart(CountDownLatch start) {
        assertTrue(start.await(10, TimeUnit.SECONDS));
    }

    @Test
    void testMoveResourceWorkflow() {
        // upload resource
        Response response = resourceRequest(HttpMethod.PUT, "/folder/conversation", CONVERSATION_BODY_1);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation\"");

        // verify resource can be downloaded
        response = resourceRequest(HttpMethod.GET, "/folder/conversation");
        verifyJson(response, 200, CONVERSATION_BODY_1);

        // verify move operation
        response = send(HttpMethod.POST, "/v1/ops/resource/move", null, """
                {
                   "sourceUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation",
                   "destinationUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder2/conversation2"
                }
                """);
        verify(response, 200);

        // verify old resource deleted
        response = resourceRequest(HttpMethod.GET, "/folder/conversation");
        verify(response, 404);

        // verify new resource can be downloaded
        response = resourceRequest(HttpMethod.GET, "/folder2/conversation2");
        verifyJson(response, 200, CONVERSATION_BODY_1);
        // verify the resource has the same author
        response = metadata("/folder2/conversation2");
        verifyNotExact(response, 200, "\"author\":\"EPM-RTC-GPT\"");
    }

    @Test
    void testMoveResourceToSameDestination() {
        // upload resource
        Response response = resourceRequest(HttpMethod.PUT, "/folder/conversation", CONVERSATION_BODY_1);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation\"");

        // verify move operation fails
        response = send(HttpMethod.POST, "/v1/ops/resource/move", null, """
                {
                   "sourceUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation",
                   "destinationUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation"
                }
                """);
        verify(response, 400);

        // verify move operation fails
        response = send(HttpMethod.POST, "/v1/ops/resource/move", null, """
                {
                   "sourceUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation",
                   "destinationUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation",
                   "overwrite": true
                }
                """);
        verify(response, 400);

        // verify resource still can be downloaded
        response = resourceRequest(HttpMethod.GET, "/folder/conversation");
        verifyJson(response, 200, CONVERSATION_BODY_1);
    }

    @Test
    void testMoveResourceWorkflowWhenDestinationResourceExists() {
        // upload resource
        Response response = resourceRequest(HttpMethod.PUT, "/folder/conversation", CONVERSATION_BODY_1);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation\"");

        response = resourceRequest(HttpMethod.PUT, "/folder2/conversation2", CONVERSATION_BODY_2);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder2/conversation2\"");

        // verify resource can be downloaded
        response = resourceRequest(HttpMethod.GET, "/folder/conversation");
        verifyJson(response, 200, CONVERSATION_BODY_1);

        response = resourceRequest(HttpMethod.GET, "/folder2/conversation2");
        verifyJson(response, 200, CONVERSATION_BODY_2);

        // verify move operation
        response = send(HttpMethod.POST, "/v1/ops/resource/move", null, """
                {
                   "sourceUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation",
                   "destinationUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder2/conversation2"
                }
                """);
        verify(response, 400);

        // verify resource can be downloaded
        response = resourceRequest(HttpMethod.GET, "/folder/conversation");
        verifyJson(response, 200, CONVERSATION_BODY_1);

        response = resourceRequest(HttpMethod.GET, "/folder2/conversation2");
        verifyJson(response, 200, CONVERSATION_BODY_2);

        response = send(HttpMethod.POST, "/v1/ops/resource/move", null, """
                {
                   "sourceUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation",
                   "destinationUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder2/conversation2",
                   "overwrite": true
                }
                """);
        verify(response, 200);

        // verify old resource deleted
        response = resourceRequest(HttpMethod.GET, "/folder/conversation");
        verify(response, 404);

        // verify new resource can be downloaded
        response = resourceRequest(HttpMethod.GET, "/folder2/conversation2");
        verifyJson(response, 200, CONVERSATION_BODY_1);
    }

    @Test
    void testMoveOperationCopySharedAccess() {
        Response response = resourceRequest(HttpMethod.PUT, "/folder/conversation", CONVERSATION_BODY_1);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation\"");

        // initialize share request
        response = operationRequest("/v1/ops/resource/share/create", """
                {
                  "invitationType": "link",
                  "resources": [
                    {
                      "url": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation"
                    }
                  ]
                }
                """);
        verify(response, 200);
        InvitationLink invitationLink = ProxyUtil.convertToObject(response.body(), InvitationLink.class);
        assertNotNull(invitationLink);

        // verify invitation details
        response = send(HttpMethod.GET, invitationLink.invitationLink(), null, null);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation\"");

        // verify user2 do not have access to the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation", null, "Api-key", "proxyKey2");
        verify(response, 403);

        // accept invitation
        response = send(HttpMethod.GET, invitationLink.invitationLink(), "accept=true", null, "Api-key", "proxyKey2");
        verify(response, 200);

        // verify user2 has access to the conversation
        response = resourceRequest(HttpMethod.GET, "/folder/conversation", null, "Api-key", "proxyKey2");
        verify(response, 200, CONVERSATION_BODY_1);

        // verify move operation
        response = send(HttpMethod.POST, "/v1/ops/resource/move", null, """
                {
                   "sourceUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation",
                   "destinationUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder2/conversation2"
                }
                """);
        verify(response, 200);

        // verify user2 has access to the moved conversation
        response = resourceRequest(HttpMethod.GET, "/folder2/conversation2", null, "Api-key", "proxyKey2");
        verify(response, 200, CONVERSATION_BODY_1);

        // verify invitation contains moved conversation
        response = send(HttpMethod.GET, invitationLink.invitationLink(), null, null);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder2/conversation2\"");

        // verify user1 has no shared_with_me resources
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["CONVERSATION"],
                  "with": "me"
                }
                """);
        verifyJson(response, 200, """
                {
                  "resources": []
                }
                """);

        // verify user2 has shared_with_me resource
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["CONVERSATION"],
                  "with": "me"
                }
                """, "Api-key", "proxyKey2");
        verifyJsonNotExact(response, 200, """
                {
                  "resources" : [ {
                    "name" : "conversation2",
                    "parentPath" : "folder2",
                    "bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "url" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder2/conversation2",
                    "nodeType" : "ITEM",
                    "createdAt" : "@ignore",
                    "updatedAt" : "@ignore",
                    "resourceType" : "CONVERSATION",
                    "permissions" : [ "READ" ]
                    } ]
                }
                """);

        // verify user1 has shared_by_me resource
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["CONVERSATION"],
                  "with": "others"
                }
                """);
        verifyJsonNotExact(response, 200, """
                {
                  "resources" : [ {
                    "name" : "conversation2",
                    "parentPath" : "folder2",
                    "bucket" : "3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST",
                    "url" : "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder2/conversation2",
                    "nodeType" : "ITEM",
                    "createdAt" : "@ignore",
                    "updatedAt" : "@ignore",
                    "resourceType" : "CONVERSATION",
                    "permissions" : [ "READ" ]
                    } ]
                }
                """);

        // verify user2 has no shared_by_me resources
        response = operationRequest("/v1/ops/resource/share/list", """
                {
                  "resourceTypes": ["CONVERSATION"],
                  "with": "others"
                }
                """, "Api-key", "proxyKey2");
        verifyJson(response, 200, """
                {
                  "resources": []
                }
                """);
    }

    @Test
    void testMoveOperationErrors() {
        // verify sourceUrl must be present
        Response response = send(HttpMethod.POST, "/v1/ops/resource/move", null, """
                {
                   "destinationUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder2/conversation2"
                }
                """);
        verify(response, 400);

        // verify destinationUrl must be present
        response = send(HttpMethod.POST, "/v1/ops/resource/move", null, """
                {
                   "sourceUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation"
                }
                """);
        verify(response, 400);

        // verify source and dest must be the same type
        response = send(HttpMethod.POST, "/v1/ops/resource/move", null, """
                {
                   "sourceUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation",
                   "destinationUrl": "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder2/conversation2"
                }
                """);
        verify(response, 400);

        // verify source must belong to the user
        response = send(HttpMethod.POST, "/v1/ops/resource/move", null, """
                {
                   "sourceUrl": "conversations/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/folder/conversation",
                   "destinationUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder2/conversation2"
                }
                """);
        verify(response, 403);

        // verify move do not support folders
        response = send(HttpMethod.POST, "/v1/ops/resource/move", null, """
                {
                   "sourceUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/",
                   "destinationUrl": "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder2/conversation2"
                }
                """);
        verify(response, 400);

        // verify sourceUrl do not exists
        response = send(HttpMethod.POST, "/v1/ops/resource/move", null, """
                {
                   "sourceUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation",
                   "destinationUrl": "files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder2/conversation2"
                }
                """);
        verify(response, 400);
    }

    @Test
    void testMoveFromPrivateToPublic() {
        Response response = resourceRequest(HttpMethod.PUT, "/folder/conversation", CONVERSATION_BODY_1);
        verify(response, 200);

        response = resourceRequest(HttpMethod.GET, "/folder/conversation");
        verifyJson(response, 200, CONVERSATION_BODY_1);

        // no access to public for ordinary user
        response = send(HttpMethod.POST, "/v1/ops/resource/move", null, """
                {
                   "sourceUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation",
                   "destinationUrl": "conversations/public/folder/conversation"
                }
                """);
        verify(response, 403);

        // admin shouldn't have access to the private conversation
        response = send(HttpMethod.POST, "/v1/ops/resource/move", null, """
                {
                   "sourceUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation",
                   "destinationUrl": "conversations/public/folder/conversation"
                }
                """, "authorization", "admin");
        verify(response, 403);

        response = resourceRequest(HttpMethod.GET, "/folder/conversation");
        verify(response, 200);

        response =  send(HttpMethod.GET, "/v1/conversations/public/folder/conversation", null, null,
                "authorization", "admin");
        verify(response, 404);
    }

    @Test
    void testMoveFromPublicToPublic() {
        Response response = send(HttpMethod.PUT, "/v1/conversations/public/folder/conversation",
                null, CONVERSATION_BODY_1, "authorization", "admin");
        verify(response, 200);

        response = send(HttpMethod.GET, "/v1/conversations/public/folder/conversation",
                null, null, "authorization", "admin");
        verifyJson(response, 200, CONVERSATION_BODY_1);

        // admin has access
        response = send(HttpMethod.POST, "/v1/ops/resource/move", null, """
                {
                   "sourceUrl": "conversations/public/folder/conversation",
                   "destinationUrl": "conversations/public/folder2/conversation2"
                }
                """, "authorization", "admin");
        verify(response, 200);

        response =  send(HttpMethod.GET, "/v1/conversations/public/folder/conversation",
                null, null, "authorization", "admin");
        verify(response, 404);

        response =  send(HttpMethod.GET, "/v1/conversations/public/folder2/conversation2",
                null, null, "authorization", "admin");
        verify(response, 200, CONVERSATION_BODY_1);
    }

    @Test
    void testCopyResourceWorkflow() {
        // upload resource
        Response response = resourceRequest(HttpMethod.PUT, "/folder/conversation", CONVERSATION_BODY_1);
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation\"");

        // verify resource can be downloaded
        response = resourceRequest(HttpMethod.GET, "/folder/conversation");
        verifyJson(response, 200, CONVERSATION_BODY_1);

        // verify copy operation
        response = send(HttpMethod.POST, "/v1/ops/resource/copy", null, """
                {
                   "sourceUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation",
                   "destinationUrl": "conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder2/conversation2"
                }
                """);
        verify(response, 200);

        // verify old resource still exists
        response = resourceRequest(HttpMethod.GET, "/folder/conversation");
        verifyJson(response, 200, CONVERSATION_BODY_1);

        // verify new resource can be downloaded
        response = resourceRequest(HttpMethod.GET, "/folder2/conversation2");
        verifyJson(response, 200, CONVERSATION_BODY_1);
        // verify the resource has the same author
        response = metadata("/folder2/conversation2");
        verifyNotExact(response, 200, "\"author\":\"EPM-RTC-GPT\"");
    }

    @Test
    void testCopyApplicationRouteSecretDecryptableAtNewPath() {
        // SecretFieldProcessor binds ciphertext to the descriptor's own absolute path (AAD) —
        // copying an application to a new path must re-encrypt under the new path, not carry the
        // source's ciphertext over verbatim, or the secret becomes permanently undecryptable there.
        Response put = send(HttpMethod.PUT,
                "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/copy-source-app", null, """
                {
                "endpoint": "http://application1/v1/completions",
                "display_name": "Copy Source App",
                "routes": {
                        "my-route": {
                          "paths": ["/v1/my-route"],
                          "methods": ["GET"],
                          "upstreams": [{"endpoint": "http://localhost:9876", "key": "copy-route-secret-1"}]
                      }
                  }
                }
                """);
        verify(put, 200);

        Response copy = send(HttpMethod.POST, "/v1/ops/resource/copy", null, """
                {
                   "sourceUrl": "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/copy-source-app",
                   "destinationUrl": "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/copy-dest-app"
                }
                """);
        verify(copy, 200);

        ResourceDescriptor destination = ResourceDescriptorFactory.fromAnyUrl(
                "applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/copy-dest-app", encryptionService);
        ApplicationService applicationService = dial.getProxy().getApplicationService();
        Application copied = applicationService.getApplication(destination).getValue();
        assertNotNull(copied, "Copied application must exist at the new path");
        assertEquals("copy-route-secret-1", copied.getRoutes().get("my-route").getUpstreams().get(0).getKey(),
                "Copied application's route secret must decrypt correctly at its new path");
    }
}
