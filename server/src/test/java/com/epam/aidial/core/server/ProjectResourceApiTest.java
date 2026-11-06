package com.epam.aidial.core.server;

import com.epam.aidial.core.server.data.InvitationLink;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.entity.mime.HttpMultipartMode;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProjectResourceApiTest extends ResourceBaseTest {

    private static final String MARKER_NAME = ".dial-resource";

    private static final String VALID_MANIFEST = """
            ---
            name: My Project
            description: Does something useful
            version: 1.0.0
            ---
            # My Project

            Body of the project.
            """;

    @Test
    void testCreateAndDownloadRoundTrip() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("README.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        files.put("scripts/run.sh", "echo hi".getBytes(StandardCharsets.UTF_8));

        Response put = uploadProject("/my-project", files);
        verify(put, 200);
        String etag = put.headers().get("etag");
        assertNotNull(etag);

        BinaryResponse zip = downloadProject("/my-project");
        assertEquals(200, zip.status());
        assertTrue(zip.headers().get("content-type").startsWith("application/zip"));
        assertEquals(etag, zip.headers().get("etag"));

        Map<String, byte[]> unpacked = unzip(zip.body());
        assertEquals(files.keySet(), unpacked.keySet());
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            assertEquals(new String(entry.getValue(), StandardCharsets.UTF_8),
                    new String(unpacked.get(entry.getKey()), StandardCharsets.UTF_8));
        }
        // the marker must not leak into the archive
        assertFalse(unpacked.containsKey(MARKER_NAME));
    }

    @Test
    void testAcceptFileSetWithoutManifest() {
        Map<String, byte[]> files = Map.of("data.txt", "x".getBytes(StandardCharsets.UTF_8));

        verify(uploadProject("/no-manifest", files), 200);

        assertEquals(200, downloadProject("/no-manifest").status());
    }

    @Test
    void testAnyFileCanBeDeleted() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("a.txt", "a".getBytes(StandardCharsets.UTF_8));
        files.put("README.md", "# hi".getBytes(StandardCharsets.UTF_8));
        verify(uploadProject("/delete-any", files), 200);

        verify(deleteProjectFile("/delete-any", "README.md"), 200);

        assertEquals(Set.of("a.txt"), unzip(downloadProject("/delete-any").body()).keySet());
    }

    @Test
    void testIfMatch() {
        Map<String, byte[]> files = Map.of("README.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));

        Response first = uploadProject("/versioned", files);
        verify(first, 200);
        String etag = first.headers().get("etag");

        // wrong If-Match -> 412
        verify(uploadProject("/versioned", files, "if-match", "\"wrong\""), 412);

        // matching If-Match -> 200
        verify(uploadProject("/versioned", files, "if-match", etag), 200);
    }

    @Test
    void testRejectReservedResourceName() {
        Map<String, byte[]> files = Map.of("README.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        verify(uploadProject("/v", files), 400);
    }

    @Test
    void testRejectReservedPartName() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("README.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        files.put(MARKER_NAME, "{}".getBytes(StandardCharsets.UTF_8));
        verify(uploadProject("/reserved-part", files), 400);
    }

    @Test
    void testNestedPathRoundTrip() {
        // The project name may span multiple path segments.
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("README.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        files.put("scripts/run.sh", "echo hi".getBytes(StandardCharsets.UTF_8));

        verify(uploadProject("/group/nested", files), 200);

        BinaryResponse zip = downloadProject("/group/nested");
        assertEquals(200, zip.status());
        assertEquals(files.keySet(), unzip(zip.body()).keySet());

        // single-file operations resolve the project name across the segments before "/files/"
        verify(putProjectFile("/group/nested", "docs/readme.md", "hi".getBytes(StandardCharsets.UTF_8)), 200);
        assertEquals("hi", new String(getProjectFile("/group/nested", "docs/readme.md").body(), StandardCharsets.UTF_8));

        verify(deleteProject("/group/nested"), 200);
        assertEquals(404, downloadProject("/group/nested").status());
    }

    @Test
    void testTrailingSlashAddressesFolder() {
        // A whole-resource upload addresses a resource by name (no trailing slash); a trailing slash now
        // addresses a DIAL grouping folder, so a multipart PUT there creates a folder (the body is ignored).
        verify(createFolder("/trailing/"), 200);
        // the whole-resource GET (no slash) on that folder is rejected
        assertEquals(400, downloadProject("/trailing").status());
    }

    @Test
    void testDelete() {
        Map<String, byte[]> files = Map.of("README.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));

        verify(uploadProject("/to-delete", files), 200);
        verify(deleteProject("/to-delete"), 200);
        assertEquals(404, downloadProject("/to-delete").status());
    }

    @Test
    void testDeleteIfMatch() {
        Map<String, byte[]> files = Map.of("README.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));

        Response put = uploadProject("/delete-etag", files);
        verify(put, 200);
        String etag = put.headers().get("etag");

        // wrong If-Match -> 412, resource still live
        verify(deleteProject("/delete-etag", "if-match", "\"wrong\""), 412);
        assertEquals(200, downloadProject("/delete-etag").status());

        // correct If-Match -> 200, resource gone
        verify(deleteProject("/delete-etag", "if-match", etag), 200);
        assertEquals(404, downloadProject("/delete-etag").status());
    }

    @Test
    void testDeleteNonExistent() {
        verify(deleteProject("/never-existed"), 404);
    }

    @Test
    void testRecreateAfterDeleteWithIfNoneMatch() {
        Map<String, byte[]> files = Map.of("README.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));

        verify(uploadProject("/recreate-me", files), 200);
        verify(deleteProject("/recreate-me"), 200);
        // path is free again -> create-only PUT must succeed, not 412
        verify(uploadProject("/recreate-me", files, "if-none-match", "*"), 200);
    }

    @Test
    void testInvisibleToV1FilesApi() {
        Map<String, byte[]> files = Map.of("README.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        verify(uploadProject("/hidden", files), 200);

        // v1 files API stores under a different blob prefix and cannot see the project
        Response viaFiles = send(HttpMethod.GET, "/v1/files/" + bucket + "/hidden/.dial-resource", null, "");
        verify(viaFiles, 404);
    }

    @Test
    void testPutNewFile() {
        Map<String, byte[]> files = Map.of("README.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        Response created = uploadProject("/file-add", files);
        verify(created, 200);
        String before = created.headers().get("etag");

        Response put = putProjectFile("/file-add", "docs/readme.md", "hello".getBytes(StandardCharsets.UTF_8));
        verify(put, 200);
        String after = put.headers().get("etag");
        assertNotNull(after);
        assertNotEquals(before, after);

        BinaryResponse got = getProjectFile("/file-add", "docs/readme.md");
        assertEquals(200, got.status());
        assertEquals("hello", new String(got.body(), StandardCharsets.UTF_8));

        // the whole-project archive now contains the added file
        Map<String, byte[]> unpacked = unzip(downloadProject("/file-add").body());
        assertTrue(unpacked.containsKey("docs/readme.md"));
    }

    @Test
    void testReplaceFile() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("README.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        files.put("data.txt", "old".getBytes(StandardCharsets.UTF_8));
        verify(uploadProject("/file-replace", files), 200);

        verify(putProjectFile("/file-replace", "data.txt", "new".getBytes(StandardCharsets.UTF_8)), 200);
        assertEquals("new", new String(getProjectFile("/file-replace", "data.txt").body(), StandardCharsets.UTF_8));
    }

    @Test
    void testDeleteFile() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("README.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        files.put("scripts/run.sh", "echo".getBytes(StandardCharsets.UTF_8));
        Response created = uploadProject("/file-del", files);
        verify(created, 200);
        String before = created.headers().get("etag");

        Response del = deleteProjectFile("/file-del", "scripts/run.sh");
        verify(del, 200);
        assertNotEquals(before, del.headers().get("etag"));

        assertEquals(404, getProjectFile("/file-del", "scripts/run.sh").status());
        Map<String, byte[]> unpacked = unzip(downloadProject("/file-del").body());
        assertFalse(unpacked.containsKey("scripts/run.sh"));
        assertTrue(unpacked.containsKey("README.md"));
    }

    @Test
    void testEditFileRefreshesEtagAndContent() {
        Map<String, byte[]> files = Map.of("README.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        Response created = uploadProject("/file-manifest", files);
        verify(created, 200);
        String before = created.headers().get("etag");

        String updated = """
                ---
                name: Renamed Project
                description: New description
                version: 2.0.0
                ---
                # Renamed
                """;
        Response put = putProjectFile("/file-manifest", "README.md", updated.getBytes(StandardCharsets.UTF_8));
        verify(put, 200);
        assertNotEquals(before, put.headers().get("etag"));
        assertEquals(updated, new String(getProjectFile("/file-manifest", "README.md").body(), StandardCharsets.UTF_8));
    }

    @Test
    void testPutFileIfMatch() {
        Map<String, byte[]> files = Map.of("README.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        Response created = uploadProject("/file-ifmatch", files);
        verify(created, 200);
        String etag = created.headers().get("etag");

        verify(putProjectFile("/file-ifmatch", "a.txt", "x".getBytes(StandardCharsets.UTF_8), "if-match", "\"wrong\""), 412);
        verify(putProjectFile("/file-ifmatch", "a.txt", "x".getBytes(StandardCharsets.UTF_8), "if-match", etag), 200);
    }

    @Test
    void testDeleteFileIfMatch() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("README.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        files.put("a.txt", "x".getBytes(StandardCharsets.UTF_8));
        Response created = uploadProject("/file-del-ifmatch", files);
        verify(created, 200);
        String etag = created.headers().get("etag");

        verify(deleteProjectFile("/file-del-ifmatch", "a.txt", "if-match", "\"wrong\""), 412);
        verify(deleteProjectFile("/file-del-ifmatch", "a.txt", "if-match", etag), 200);
    }

    @Test
    void testSingleFileOnAbsentResource() {
        assertEquals(404, getProjectFile("/absent", "a.txt").status());
        verify(putProjectFile("/absent", "a.txt", "x".getBytes(StandardCharsets.UTF_8)), 404);
        verify(deleteProjectFile("/absent", "a.txt"), 404);
    }

    @Test
    void testSingleFileOnDeletedResource() {
        Map<String, byte[]> files = Map.of("README.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        verify(uploadProject("/file-after-delete", files), 200);
        verify(deleteProject("/file-after-delete"), 200);

        assertEquals(404, getProjectFile("/file-after-delete", "README.md").status());
        verify(putProjectFile("/file-after-delete", "a.txt", "x".getBytes(StandardCharsets.UTF_8)), 404);
        verify(deleteProjectFile("/file-after-delete", "a.txt"), 404);
    }

    @Test
    void testMetadataListingClassifiesChildren() {
        Map<String, byte[]> files = Map.of("README.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        // deep PUT auto-vivifies the "cat" grouping folder
        verify(uploadProject("/cat/project-a", files), 200);
        verify(uploadProject("/cat/project-b", files), 200);
        verify(createFolder("/cat/sub/"), 200);

        // a DIAL resource is reported as ITEM, a grouping folder as FOLDER, classified from the marker file
        Response listing = listMetadata("cat");
        verify(listing, 200);
        Map<String, String> nodeTypes = childNodeTypes(listing);
        assertEquals(Set.of("project-a", "project-b", "sub"), nodeTypes.keySet());
        assertEquals("ITEM", nodeTypes.get("project-a"));
        assertEquals("ITEM", nodeTypes.get("project-b"));
        assertEquals("FOLDER", nodeTypes.get("sub"));
    }

    @Test
    void testMetadataOfProjectItselfIsItem() {
        Map<String, byte[]> files = Map.of("README.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        verify(uploadProject("/cat/project-a", files), 200);
        verify(createFolder("/cat/sub/"), 200);

        // requesting the project's own path (not its parent) must report the project itself as an ITEM,
        // not an (empty) FOLDER
        Response projectMetadata = listMetadata("cat/project-a");
        verify(projectMetadata, 200);
        assertEquals("ITEM", nodeType(projectMetadata));

        // a grouping folder's own path must still report itself as a FOLDER
        Response folderMetadata = listMetadata("cat/sub");
        verify(folderMetadata, 200);
        assertEquals("FOLDER", nodeType(folderMetadata));
    }

    @Test
    void testMetadataListingRecursive() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("README.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        files.put("scripts/run.sh", "echo hi".getBytes(StandardCharsets.UTF_8));
        verify(uploadProject("/tree/project-a", files), 200);
        verify(uploadProject("/tree/sub/nested", files), 200);

        // non-recursive: only the immediate children (a resource and a grouping folder)
        Response listing = listMetadata("tree");
        assertEquals(Set.of("project-a", "sub"), childNodeTypes(listing).keySet());

        // recursive: every DIAL resource/folder in the subtree, and never the files inside a resource
        Set<String> urls = childUrls(send(HttpMethod.GET,
                "/v2/metadata/projects/" + bucket + "/tree", "recursive=true", ""));
        assertTrue(urls.contains("projects/" + bucket + "/tree/project-a/"), urls.toString());
        assertTrue(urls.contains("projects/" + bucket + "/tree/sub/"), urls.toString());
        assertTrue(urls.contains("projects/" + bucket + "/tree/sub/nested/"), urls.toString());
        // resource files (README.md, scripts/run.sh) and the internal v/ version tree must be excluded
        assertTrue(urls.stream().noneMatch(u -> u.contains("README.md") || u.contains("/v/") || u.contains("run.sh")),
                urls.toString());
    }

    @Test
    void testMetadataListingOmitsDeletedResources() {
        Map<String, byte[]> files = Map.of("README.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        verify(uploadProject("/grp/keep", files), 200);
        verify(uploadProject("/grp/gone", files), 200);
        verify(deleteProject("/grp/gone"), 200);

        assertEquals(Set.of("keep"), childNodeTypes(listMetadata("grp")).keySet());
    }

    @Test
    void testMetadataFilesListing() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("README.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        files.put("scripts/run.sh", "echo hi".getBytes(StandardCharsets.UTF_8));
        verify(uploadProject("/files-project", files), 200);

        // non-recursive: immediate entries of the version, under a clean .../files/ url (version prefix hidden)
        Response listing = listProjectFiles("/files-project");
        Set<String> immediate = childUrls(listing);
        assertTrue(immediate.contains("projects/" + bucket + "/files-project/files/README.md"), immediate.toString());
        assertTrue(immediate.contains("projects/" + bucket + "/files-project/files/scripts/"), immediate.toString());

        // nodeType must distinguish files from subfolders, just like the v1 metadata API
        Map<String, String> nodeTypes = childNodeTypes(listing);
        assertEquals("ITEM", nodeTypes.get("README.md"));
        assertEquals("FOLDER", nodeTypes.get("scripts"));

        // recursive: all files flattened
        Set<String> all = childUrls(send(HttpMethod.GET,
                "/v2/metadata/projects/" + bucket + "/files-project/files", "recursive=true", ""));
        assertTrue(all.contains("projects/" + bucket + "/files-project/files/README.md"), all.toString());
        assertTrue(all.contains("projects/" + bucket + "/files-project/files/scripts/run.sh"), all.toString());

        // files listing of an absent resource -> 404
        assertEquals(404, listProjectFiles("/no-such-project").status());
    }

    @Test
    void testAutoVivifyIntermediateFolders() {
        Map<String, byte[]> files = Map.of("README.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        verify(uploadProject("/a/b/deep", files), 200);

        assertEquals("FOLDER", childNodeTypes(listMetadata("a")).get("b"));
        assertEquals("ITEM", childNodeTypes(listMetadata("a/b")).get("deep"));

        // a second deep PUT reuses the vivified folders
        verify(uploadProject("/a/b/deep2", files), 200);
        assertEquals(Set.of("deep", "deep2"), childNodeTypes(listMetadata("a/b")).keySet());
    }

    @Test
    void testRejectResourceInsideResource() {
        Map<String, byte[]> files = Map.of("README.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        verify(uploadProject("/outer", files), 200);
        // a resource must never be created inside another resource
        verify(uploadProject("/outer/inner", files), 400);
    }

    @Test
    void testRejectNameCollisions() {
        Map<String, byte[]> files = Map.of("README.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));

        // project exists -> folder create at the same path is rejected
        verify(uploadProject("/collide-a", files), 200);
        verify(createFolder("/collide-a/"), 400);

        // folder exists -> project PUT at the same path is rejected
        verify(createFolder("/collide-b/"), 200);
        verify(uploadProject("/collide-b", files), 400);

        // folder exists -> a second folder create is rejected
        verify(createFolder("/collide-b/"), 400);
    }

    @Test
    void testDeleteEmptyFolder() {
        verify(createFolder("/empty/"), 200);
        verify(deleteFolder("/empty/"), 200);
        // gone from the parent listing (root)
        assertFalse(childNodeTypes(listMetadata("")).containsKey("empty"));
    }

    @Test
    void testDeleteNonEmptyFolderRejected() {
        Map<String, byte[]> files = Map.of("README.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        verify(uploadProject("/parent/child", files), 200);

        // the folder holds a child resource -> 409, and the child is untouched (no cascade)
        verify(deleteFolder("/parent/"), 409);
        assertEquals(200, downloadProject("/parent/child").status());
    }

    @Test
    void testDeleteFolderIfMatch() {
        Response created = createFolder("/etag-folder/");
        verify(created, 200);
        String etag = created.headers().get("etag");
        assertNotNull(etag);

        verify(deleteFolder("/etag-folder/", "if-match", "\"wrong\""), 412);
        verify(deleteFolder("/etag-folder/", "if-match", etag), 200);
    }

    @Test
    void testGetOnFolderRejected() {
        verify(createFolder("/as-folder/"), 200);
        // whole-resource GET (no trailing slash) on a folder -> 400
        assertEquals(400, downloadProject("/as-folder").status());
        // trailing-slash GET -> 400
        assertEquals(400, send(HttpMethod.GET, "/v2/projects/" + bucket + "/as-folder/", null, "").status());
    }

    @Test
    void testOwnerAccessAndDenialForOtherUser() {
        Map<String, byte[]> files = Map.of("README.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        // owner (default user) can create and read the project by bucket-location match, no marker walk needed
        Response created = uploadProject("/private-project", files);
        verify(created, 200);
        assertEquals(200, downloadProject("/private-project").status());

        // another user has no access: whole-resource, single-file and metadata ops are all forbidden, not merely "not found"
        assertEquals(403, downloadProject("/private-project", "Api-key", "proxyKey2").status());
        assertEquals(403, getProjectFile("/private-project", "README.md", "Api-key", "proxyKey2").status());
        verify(listMetadata("private-project", "Api-key", "proxyKey2"), 403);
        verify(uploadProject("/private-project", files, "Api-key", "proxyKey2"), 403);
        verify(putProjectFile("/private-project", "a.txt", "x".getBytes(StandardCharsets.UTF_8), "Api-key", "proxyKey2"), 403);
        verify(deleteProject("/private-project", "Api-key", "proxyKey2"), 403);
    }

    @Test
    void testFolderShareInheritsFileAccess() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("README.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        files.put("scripts/run.sh", "echo hi".getBytes(StandardCharsets.UTF_8));
        verify(uploadProject("/shared-group/project-a", files), 200);

        // before sharing, the other user has no access
        assertEquals(403, downloadProject("/shared-group/project-a", "Api-key", "proxyKey2").status());

        // share the grouping folder (not the project itself)
        Response share = operationRequest("/v1/ops/resource/share/create", """
                {
                  "invitationType": "link",
                  "resources": [
                    { "url": "projects/%s/shared-group/" }
                  ]
                }
                """.formatted(bucket));
        verify(share, 200);
        InvitationLink invitationLink = ProxyUtil.convertToObject(share.body(), InvitationLink.class);
        assertNotNull(invitationLink);

        verify(send(HttpMethod.GET, invitationLink.invitationLink(), "accept=true", null, "Api-key", "proxyKey2"), 200);

        // access to the project, to a file beneath it, and to the folder's metadata listing is now inherited
        // from the folder share, via the same recursive parent-permission lookup used for v1 resources
        assertEquals(200, downloadProject("/shared-group/project-a", "Api-key", "proxyKey2").status());
        assertEquals("echo hi", new String(
                getProjectFile("/shared-group/project-a", "scripts/run.sh", "Api-key", "proxyKey2").body(), StandardCharsets.UTF_8));
        verify(listMetadata("shared-group", "Api-key", "proxyKey2"), 200);
    }

    @Test
    void testRevokeSharedAccess() {
        Map<String, byte[]> files = Map.of("README.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        verify(uploadProject("/revoke-project", files), 200);

        // before sharing, the other user has no access
        assertEquals(403, downloadProject("/revoke-project", "Api-key", "proxyKey2").status());

        // share the project itself (the whole resource, not just its containing folder)
        Response share = operationRequest("/v1/ops/resource/share/create", """
                {
                  "invitationType": "link",
                  "resources": [
                    { "url": "projects/%s/revoke-project" }
                  ]
                }
                """.formatted(bucket));
        verify(share, 200);
        InvitationLink invitationLink = ProxyUtil.convertToObject(share.body(), InvitationLink.class);
        assertNotNull(invitationLink);

        verify(send(HttpMethod.GET, invitationLink.invitationLink(), "accept=true", null, "Api-key", "proxyKey2"), 200);

        // access is granted after accepting the invitation
        assertEquals(200, downloadProject("/revoke-project", "Api-key", "proxyKey2").status());

        // explicitly revoke the share
        Response revoke = operationRequest("/v1/ops/resource/share/revoke", """
                {
                  "resources": [
                    { "url": "projects/%s/revoke-project" }
                  ]
                }
                """.formatted(bucket));
        verify(revoke, 200);

        // access to the project and its files is removed after revoke
        assertEquals(403, downloadProject("/revoke-project", "Api-key", "proxyKey2").status());
        assertEquals(403, getProjectFile("/revoke-project", "README.md", "Api-key", "proxyKey2").status());
    }

    @Test
    void testItemShareInheritsMetadataAccess() {
        Map<String, byte[]> files = Map.of("README.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        verify(uploadProject("/shared-item/project-a", files), 200);

        // before sharing, an unrelated user has no access to the item's metadata
        assertEquals(403, listMetadata("shared-item/project-a", "Api-key", "proxyKey2").status());

        // share the project item itself, not its containing folder
        Response share = operationRequest("/v1/ops/resource/share/create", """
                {
                  "invitationType": "link",
                  "resources": [
                    { "url": "projects/%s/shared-item/project-a" }
                  ]
                }
                """.formatted(bucket));
        verify(share, 200);
        InvitationLink invitationLink = ProxyUtil.convertToObject(share.body(), InvitationLink.class);
        assertNotNull(invitationLink);

        verify(send(HttpMethod.GET, invitationLink.invitationLink(), "accept=true", null, "Api-key", "proxyKey2"), 200);

        // the item's own metadata is now visible to the invited user, correctly classified as an ITEM
        Response metadata = listMetadata("shared-item/project-a", "Api-key", "proxyKey2");
        verify(metadata, 200);
        assertEquals("ITEM", nodeType(metadata));
    }

    @Test
    void testCleanUpShareAccessWhenOnResourceDeletion() {
        Map<String, byte[]> files = Map.of("README.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        verify(uploadProject("/delete-cleanup-project", files), 200);

        Response share = operationRequest("/v1/ops/resource/share/create", """
                {
                  "invitationType": "link",
                  "resources": [
                    { "url": "projects/%s/delete-cleanup-project" }
                  ]
                }
                """.formatted(bucket));
        verify(share, 200);
        InvitationLink invitationLink = ProxyUtil.convertToObject(share.body(), InvitationLink.class);
        assertNotNull(invitationLink);

        verify(send(HttpMethod.GET, invitationLink.invitationLink(), "accept=true", null, "Api-key", "proxyKey2"), 200);
        assertEquals(200, downloadProject("/delete-cleanup-project", "Api-key", "proxyKey2").status());

        // delete the shared project
        verify(deleteProject("/delete-cleanup-project"), 200);

        // recreate a project at the same path
        verify(uploadProject("/delete-cleanup-project", files), 200);

        // the other user no longer has access: whole-resource delete already cleaned up the share metadata
        assertEquals(403, downloadProject("/delete-cleanup-project", "Api-key", "proxyKey2").status());
    }

    private Response listMetadata(String relativePath, String... headers) {
        return send(HttpMethod.GET, "/v2/metadata/projects/" + bucket + "/" + relativePath, null, "", headers);
    }

    private Response listProjectFiles(String projectPath, String... headers) {
        return send(HttpMethod.GET, "/v2/metadata/projects/" + bucket + projectPath + "/files", null, "", headers);
    }

    private Response createFolder(String folderPath, String... headers) {
        return send(HttpMethod.PUT, "/v2/projects/" + bucket + folderPath, null, "", headers);
    }

    private Response deleteFolder(String folderPath, String... headers) {
        return send(HttpMethod.DELETE, "/v2/projects/" + bucket + folderPath, null, "", headers);
    }

    @SneakyThrows
    private static String nodeType(Response metadata) {
        return ProxyUtil.MAPPER.readTree(metadata.body()).get("nodeType").asText();
    }

    @SneakyThrows
    private static Map<String, String> childNodeTypes(Response listing) {
        Map<String, String> result = new HashMap<>();
        for (JsonNode item : ProxyUtil.MAPPER.readTree(listing.body()).get("items")) {
            result.put(item.get("name").asText(), item.get("nodeType").asText());
        }
        return result;
    }

    @SneakyThrows
    private static Set<String> childUrls(Response listing) {
        Set<String> urls = new HashSet<>();
        for (JsonNode item : ProxyUtil.MAPPER.readTree(listing.body()).get("items")) {
            urls.add(item.get("url").asText());
        }
        return urls;
    }

    @SneakyThrows
    private Response uploadProject(String projectPath, Map<String, byte[]> files, String... headers) {
        String uri = "http://127.0.0.1:" + serverPort + "/v2/projects/" + bucket + projectPath;
        HttpUriRequest request = new HttpUriRequestBase(HttpMethod.PUT.name(), URI.create(uri));

        for (int i = 0; i < headers.length; i += 2) {
            request.setHeader(headers[i], headers[i + 1]);
        }
        if (!request.containsHeader("authorization") && !request.containsHeader("api-key")) {
            request.setHeader("api-key", "proxyKey1");
        }

        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.setMode(HttpMultipartMode.LEGACY);
        builder.setCharset(StandardCharsets.UTF_8);
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            builder.addBinaryBody(entry.getKey(), entry.getValue(), ContentType.DEFAULT_BINARY, entry.getKey());
        }
        request.setEntity(builder.build());

        return client.execute(request, ResourceBaseTest::toResponse);
    }

    @SneakyThrows
    private Response deleteProject(String projectPath, String... headers) {
        String uri = "http://127.0.0.1:" + serverPort + "/v2/projects/" + bucket + projectPath;
        HttpUriRequest request = new HttpUriRequestBase(HttpMethod.DELETE.name(), URI.create(uri));

        for (int i = 0; i < headers.length; i += 2) {
            request.setHeader(headers[i], headers[i + 1]);
        }
        if (!request.containsHeader("authorization") && !request.containsHeader("api-key")) {
            request.setHeader("api-key", "proxyKey1");
        }

        return client.execute(request, ResourceBaseTest::toResponse);
    }

    @SneakyThrows
    private BinaryResponse downloadProject(String projectPath, String... headers) {
        String uri = "http://127.0.0.1:" + serverPort + "/v2/projects/" + bucket + projectPath;
        HttpUriRequest request = new HttpUriRequestBase(HttpMethod.GET.name(), URI.create(uri));

        for (int i = 0; i < headers.length; i += 2) {
            request.setHeader(headers[i], headers[i + 1]);
        }
        if (!request.containsHeader("authorization") && !request.containsHeader("api-key")) {
            request.setHeader("api-key", "proxyKey1");
        }

        return client.execute(request, response -> {
            int status = response.getCode();
            byte[] body = response.getEntity() == null ? new byte[0] : EntityUtils.toByteArray(response.getEntity());
            Map<String, String> responseHeaders = new HashMap<>();
            for (Header header : response.getHeaders()) {
                responseHeaders.put(header.getName(), header.getValue());
            }
            return new BinaryResponse(status, body, responseHeaders);
        });
    }

    @SneakyThrows
    private Response putProjectFile(String projectPath, String filePath, byte[] content, String... headers) {
        String uri = "http://127.0.0.1:" + serverPort + "/v2/projects/" + bucket + projectPath + "/files/" + filePath;
        HttpUriRequest request = new HttpUriRequestBase(HttpMethod.PUT.name(), URI.create(uri));

        for (int i = 0; i < headers.length; i += 2) {
            request.setHeader(headers[i], headers[i + 1]);
        }
        if (!request.containsHeader("authorization") && !request.containsHeader("api-key")) {
            request.setHeader("api-key", "proxyKey1");
        }

        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.setMode(HttpMultipartMode.LEGACY);
        builder.setCharset(StandardCharsets.UTF_8);
        builder.addBinaryBody("file", content, ContentType.DEFAULT_BINARY, "file");
        request.setEntity(builder.build());

        return client.execute(request, ResourceBaseTest::toResponse);
    }

    @SneakyThrows
    private Response deleteProjectFile(String projectPath, String filePath, String... headers) {
        String uri = "http://127.0.0.1:" + serverPort + "/v2/projects/" + bucket + projectPath + "/files/" + filePath;
        HttpUriRequest request = new HttpUriRequestBase(HttpMethod.DELETE.name(), URI.create(uri));

        for (int i = 0; i < headers.length; i += 2) {
            request.setHeader(headers[i], headers[i + 1]);
        }
        if (!request.containsHeader("authorization") && !request.containsHeader("api-key")) {
            request.setHeader("api-key", "proxyKey1");
        }

        return client.execute(request, ResourceBaseTest::toResponse);
    }

    @SneakyThrows
    private BinaryResponse getProjectFile(String projectPath, String filePath, String... headers) {
        String uri = "http://127.0.0.1:" + serverPort + "/v2/projects/" + bucket + projectPath + "/files/" + filePath;
        HttpUriRequest request = new HttpUriRequestBase(HttpMethod.GET.name(), URI.create(uri));

        for (int i = 0; i < headers.length; i += 2) {
            request.setHeader(headers[i], headers[i + 1]);
        }
        if (!request.containsHeader("authorization") && !request.containsHeader("api-key")) {
            request.setHeader("api-key", "proxyKey1");
        }

        return client.execute(request, response -> {
            int status = response.getCode();
            byte[] body = response.getEntity() == null ? new byte[0] : EntityUtils.toByteArray(response.getEntity());
            Map<String, String> responseHeaders = new HashMap<>();
            for (Header header : response.getHeaders()) {
                responseHeaders.put(header.getName(), header.getValue());
            }
            return new BinaryResponse(status, body, responseHeaders);
        });
    }

    @SneakyThrows
    private static Map<String, byte[]> unzip(byte[] archive) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                result.put(entry.getName(), zip.readAllBytes());
                zip.closeEntry();
            }
        }
        return result;
    }

    private record BinaryResponse(int status, byte[] body, Map<String, String> headers) {
    }

    /**
     * Boundary tests for the per-resource {@code maxFiles}/{@code maxTotalBytes}/{@code maxFileSizeBytes} limits,
     * run against small overridden limits so the boundaries are cheap to exercise.
     */
    public static class LimitsApiTest extends ResourceBaseTest {

        private static final String MANIFEST = """
                ---
                name: My Project
                description: Does something useful
                ---
                """;

        @Override
        protected JsonObject additionalSettingsOverrides() {
            return new JsonObject("""
                    {
                      "complexResource": {
                        "types": {
                          "projects": {
                            "maxFiles": 3,
                            "maxTotalBytes": 200,
                            "maxFileSizeBytes": 100
                          }
                        }
                      }
                    }
                    """);
        }

        @Test
        void testWholeResourcePutRejectsExceedingMaxFiles() {
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("README.md", MANIFEST.getBytes(StandardCharsets.UTF_8));
            files.put("a.txt", "a".getBytes(StandardCharsets.UTF_8));
            files.put("b.txt", "b".getBytes(StandardCharsets.UTF_8));
            files.put("c.txt", "c".getBytes(StandardCharsets.UTF_8));

            verify(uploadProject("/too-many-files", files), 400);
            assertEquals(404, downloadProject("/too-many-files").status());
        }

        @Test
        void testWholeResourcePutAcceptsExactlyMaxFiles() {
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("README.md", MANIFEST.getBytes(StandardCharsets.UTF_8));
            files.put("a.txt", "a".getBytes(StandardCharsets.UTF_8));
            files.put("b.txt", "b".getBytes(StandardCharsets.UTF_8));

            verify(uploadProject("/exact-max-files", files), 200);
        }

        @Test
        void testWholeResourcePutRejectsExceedingMaxTotalBytes() {
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("README.md", MANIFEST.getBytes(StandardCharsets.UTF_8));
            files.put("big.txt", "x".repeat(200).getBytes(StandardCharsets.UTF_8));

            Response put = uploadProject("/too-big-total", files);
            verify(put, 413);
            assertEquals(404, downloadProject("/too-big-total").status());
        }

        @Test
        void testWholeResourcePutRejectsExceedingMaxFileSize() {
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("README.md", MANIFEST.getBytes(StandardCharsets.UTF_8));
            files.put("huge.txt", "x".repeat(150).getBytes(StandardCharsets.UTF_8));

            Response put = uploadProject("/too-big-file", files);
            verify(put, 413);
            assertEquals(404, downloadProject("/too-big-file").status());
        }

        @Test
        void testSingleFilePutRejectsExceedingMaxFileSize() {
            Map<String, byte[]> files = Map.of("README.md", MANIFEST.getBytes(StandardCharsets.UTF_8));
            verify(uploadProject("/file-too-big", files), 200);

            verify(putProjectFile("/file-too-big", "big.txt", "x".repeat(150).getBytes(StandardCharsets.UTF_8)), 413);
            // rejected mutation left nothing observable: the file was never added
            assertEquals(404, getProjectFile("/file-too-big", "big.txt").status());
        }

        @Test
        void testSingleFilePutRejectsExceedingMaxTotalBytes() {
            Map<String, byte[]> files = Map.of("README.md", MANIFEST.getBytes(StandardCharsets.UTF_8));
            Response created = uploadProject("/file-total-too-big", files);
            verify(created, 200);
            String before = created.headers().get("etag");

            // README.md is small; adding a near-limit file tips the aggregate past maxTotalBytes(200)
            verify(putProjectFile("/file-total-too-big", "big.txt", "x".repeat(190).getBytes(StandardCharsets.UTF_8)), 413);

            // rejected mutation must not have changed the resource
            assertEquals(before, downloadProject("/file-total-too-big").headers().get("etag"));
        }

        @Test
        void testSingleFilePutRejectsExceedingMaxFiles() {
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("README.md", MANIFEST.getBytes(StandardCharsets.UTF_8));
            files.put("a.txt", "a".getBytes(StandardCharsets.UTF_8));
            files.put("b.txt", "b".getBytes(StandardCharsets.UTF_8));
            verify(uploadProject("/file-count-too-big", files), 200);

            // adding a 4th distinct file exceeds maxFiles(3)
            verify(putProjectFile("/file-count-too-big", "c.txt", "c".getBytes(StandardCharsets.UTF_8)), 400);

            // replacing an existing file is fine (file count unchanged)
            verify(putProjectFile("/file-count-too-big", "a.txt", "aa".getBytes(StandardCharsets.UTF_8)), 200);
        }

        @SneakyThrows
        private Response uploadProject(String projectPath, Map<String, byte[]> files, String... headers) {
            String uri = "http://127.0.0.1:" + serverPort + "/v2/projects/" + bucket + projectPath;
            HttpUriRequest request = new HttpUriRequestBase(HttpMethod.PUT.name(), URI.create(uri));

            for (int i = 0; i < headers.length; i += 2) {
                request.setHeader(headers[i], headers[i + 1]);
            }
            if (!request.containsHeader("authorization") && !request.containsHeader("api-key")) {
                request.setHeader("api-key", "proxyKey1");
            }

            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            builder.setMode(HttpMultipartMode.LEGACY);
            builder.setCharset(StandardCharsets.UTF_8);
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                builder.addBinaryBody(entry.getKey(), entry.getValue(), ContentType.DEFAULT_BINARY, entry.getKey());
            }
            request.setEntity(builder.build());

            return client.execute(request, ResourceBaseTest::toResponse);
        }

        @SneakyThrows
        private BinaryResponse downloadProject(String projectPath, String... headers) {
            String uri = "http://127.0.0.1:" + serverPort + "/v2/projects/" + bucket + projectPath;
            HttpUriRequest request = new HttpUriRequestBase(HttpMethod.GET.name(), URI.create(uri));

            for (int i = 0; i < headers.length; i += 2) {
                request.setHeader(headers[i], headers[i + 1]);
            }
            if (!request.containsHeader("authorization") && !request.containsHeader("api-key")) {
                request.setHeader("api-key", "proxyKey1");
            }

            return client.execute(request, response -> {
                int status = response.getCode();
                byte[] body = response.getEntity() == null ? new byte[0] : EntityUtils.toByteArray(response.getEntity());
                Map<String, String> responseHeaders = new HashMap<>();
                for (Header header : response.getHeaders()) {
                    responseHeaders.put(header.getName(), header.getValue());
                }
                return new BinaryResponse(status, body, responseHeaders);
            });
        }

        @SneakyThrows
        private Response putProjectFile(String projectPath, String filePath, byte[] content, String... headers) {
            String uri = "http://127.0.0.1:" + serverPort + "/v2/projects/" + bucket + projectPath + "/files/" + filePath;
            HttpUriRequest request = new HttpUriRequestBase(HttpMethod.PUT.name(), URI.create(uri));

            for (int i = 0; i < headers.length; i += 2) {
                request.setHeader(headers[i], headers[i + 1]);
            }
            if (!request.containsHeader("authorization") && !request.containsHeader("api-key")) {
                request.setHeader("api-key", "proxyKey1");
            }

            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            builder.setMode(HttpMultipartMode.LEGACY);
            builder.setCharset(StandardCharsets.UTF_8);
            builder.addBinaryBody("file", content, ContentType.DEFAULT_BINARY, "file");
            request.setEntity(builder.build());

            return client.execute(request, ResourceBaseTest::toResponse);
        }

        @SneakyThrows
        private BinaryResponse getProjectFile(String projectPath, String filePath, String... headers) {
            String uri = "http://127.0.0.1:" + serverPort + "/v2/projects/" + bucket + projectPath + "/files/" + filePath;
            HttpUriRequest request = new HttpUriRequestBase(HttpMethod.GET.name(), URI.create(uri));

            for (int i = 0; i < headers.length; i += 2) {
                request.setHeader(headers[i], headers[i + 1]);
            }
            if (!request.containsHeader("authorization") && !request.containsHeader("api-key")) {
                request.setHeader("api-key", "proxyKey1");
            }

            return client.execute(request, response -> {
                int status = response.getCode();
                byte[] body = response.getEntity() == null ? new byte[0] : EntityUtils.toByteArray(response.getEntity());
                Map<String, String> responseHeaders = new HashMap<>();
                for (Header header : response.getHeaders()) {
                    responseHeaders.put(header.getName(), header.getValue());
                }
                return new BinaryResponse(status, body, responseHeaders);
            });
        }

        private record BinaryResponse(int status, byte[] body, Map<String, String> headers) {
        }
    }
}
