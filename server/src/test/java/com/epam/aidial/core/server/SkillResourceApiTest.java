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

public class SkillResourceApiTest extends ResourceBaseTest {

    private static final String MARKER_NAME = ".dial-resource";

    private static final String VALID_MANIFEST = """
            ---
            name: My Skill
            description: Does something useful
            version: 1.0.0
            ---
            # My Skill

            Body of the skill.
            """;

    @Test
    void testCreateAndDownloadRoundTrip() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        files.put("scripts/run.sh", "echo hi".getBytes(StandardCharsets.UTF_8));

        Response put = uploadSkill("/my-skill", files);
        verify(put, 200);
        String etag = put.headers().get("etag");
        assertNotNull(etag);

        BinaryResponse zip = downloadSkill("/my-skill");
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
    void testRejectMissingManifest() {
        Map<String, byte[]> files = Map.of("data.txt", "x".getBytes(StandardCharsets.UTF_8));

        Response put = uploadSkill("/no-manifest", files);
        verify(put, 400);

        // nothing observable was written
        assertEquals(404, downloadSkill("/no-manifest").status());
    }

    @Test
    void testRejectIncompleteFrontmatter() {
        String manifest = """
                ---
                name: Only Name
                ---
                """;
        Map<String, byte[]> files = Map.of("SKILL.md", manifest.getBytes(StandardCharsets.UTF_8));

        Response put = uploadSkill("/bad-frontmatter", files);
        verify(put, 400);
        assertEquals(404, downloadSkill("/bad-frontmatter").status());
    }

    @Test
    void testRejectUnparseableFrontmatter() {
        Map<String, byte[]> files = Map.of("SKILL.md", "# no frontmatter here".getBytes(StandardCharsets.UTF_8));

        verify(uploadSkill("/no-frontmatter", files), 400);
    }

    @Test
    void testIfMatch() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));

        Response first = uploadSkill("/versioned", files);
        verify(first, 200);
        String etag = first.headers().get("etag");

        // wrong If-Match -> 412
        verify(uploadSkill("/versioned", files, "if-match", "\"wrong\""), 412);

        // matching If-Match -> 200
        verify(uploadSkill("/versioned", files, "if-match", etag), 200);
    }

    @Test
    void testRejectReservedResourceName() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        verify(uploadSkill("/v", files), 400);
    }

    @Test
    void testRejectReservedPartName() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        files.put(MARKER_NAME, "{}".getBytes(StandardCharsets.UTF_8));
        verify(uploadSkill("/reserved-part", files), 400);
    }

    @Test
    void testNestedPathRoundTrip() {
        // The skill name may span multiple path segments.
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        files.put("scripts/run.sh", "echo hi".getBytes(StandardCharsets.UTF_8));

        verify(uploadSkill("/group/nested", files), 200);

        BinaryResponse zip = downloadSkill("/group/nested");
        assertEquals(200, zip.status());
        assertEquals(files.keySet(), unzip(zip.body()).keySet());

        // single-file operations resolve the skill name across the segments before "/files/"
        verify(putSkillFile("/group/nested", "docs/readme.md", "hi".getBytes(StandardCharsets.UTF_8)), 200);
        assertEquals("hi", new String(getSkillFile("/group/nested", "docs/readme.md").body(), StandardCharsets.UTF_8));

        verify(deleteSkill("/group/nested"), 200);
        assertEquals(404, downloadSkill("/group/nested").status());
    }

    @Test
    void testTrailingSlashAddressesFolder() {
        // A whole-resource upload addresses a resource by name (no trailing slash); a trailing slash now
        // addresses a DIAL grouping folder, so a multipart PUT there creates a folder (the body is ignored).
        verify(createFolder("/trailing/"), 200);
        // the whole-resource GET (no slash) on that folder is rejected
        assertEquals(400, downloadSkill("/trailing").status());
    }

    @Test
    void testDelete() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));

        verify(uploadSkill("/to-delete", files), 200);
        verify(deleteSkill("/to-delete"), 200);
        assertEquals(404, downloadSkill("/to-delete").status());
    }

    @Test
    void testDeleteIfMatch() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));

        Response put = uploadSkill("/delete-etag", files);
        verify(put, 200);
        String etag = put.headers().get("etag");

        // wrong If-Match -> 412, resource still live
        verify(deleteSkill("/delete-etag", "if-match", "\"wrong\""), 412);
        assertEquals(200, downloadSkill("/delete-etag").status());

        // correct If-Match -> 200, resource gone
        verify(deleteSkill("/delete-etag", "if-match", etag), 200);
        assertEquals(404, downloadSkill("/delete-etag").status());
    }

    @Test
    void testDeleteNonExistent() {
        verify(deleteSkill("/never-existed"), 404);
    }

    @Test
    void testInvisibleToV1FilesApi() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        verify(uploadSkill("/hidden", files), 200);

        // v1 files API stores under a different blob prefix and cannot see the skill
        Response viaFiles = send(HttpMethod.GET, "/v1/files/" + bucket + "/hidden/.dial-resource", null, "");
        verify(viaFiles, 404);
    }

    @Test
    void testPutNewFile() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        Response created = uploadSkill("/file-add", files);
        verify(created, 200);
        String before = created.headers().get("etag");

        Response put = putSkillFile("/file-add", "docs/readme.md", "hello".getBytes(StandardCharsets.UTF_8));
        verify(put, 200);
        String after = put.headers().get("etag");
        assertNotNull(after);
        assertNotEquals(before, after);

        BinaryResponse got = getSkillFile("/file-add", "docs/readme.md");
        assertEquals(200, got.status());
        assertEquals("hello", new String(got.body(), StandardCharsets.UTF_8));

        // the whole-skill archive now contains the added file
        Map<String, byte[]> unpacked = unzip(downloadSkill("/file-add").body());
        assertTrue(unpacked.containsKey("docs/readme.md"));
    }

    @Test
    void testReplaceFile() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        files.put("data.txt", "old".getBytes(StandardCharsets.UTF_8));
        verify(uploadSkill("/file-replace", files), 200);

        verify(putSkillFile("/file-replace", "data.txt", "new".getBytes(StandardCharsets.UTF_8)), 200);
        assertEquals("new", new String(getSkillFile("/file-replace", "data.txt").body(), StandardCharsets.UTF_8));
    }

    @Test
    void testDeleteFile() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        files.put("scripts/run.sh", "echo".getBytes(StandardCharsets.UTF_8));
        Response created = uploadSkill("/file-del", files);
        verify(created, 200);
        String before = created.headers().get("etag");

        Response del = deleteSkillFile("/file-del", "scripts/run.sh");
        verify(del, 200);
        assertNotEquals(before, del.headers().get("etag"));

        assertEquals(404, getSkillFile("/file-del", "scripts/run.sh").status());
        Map<String, byte[]> unpacked = unzip(downloadSkill("/file-del").body());
        assertFalse(unpacked.containsKey("scripts/run.sh"));
        assertTrue(unpacked.containsKey("SKILL.md"));
    }

    @Test
    void testDeleteManifestRejected() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        Response created = uploadSkill("/file-guard", files);
        verify(created, 200);
        String before = created.headers().get("etag");

        verify(deleteSkillFile("/file-guard", "SKILL.md"), 400);

        // the skill is unchanged
        BinaryResponse zip = downloadSkill("/file-guard");
        assertEquals(200, zip.status());
        assertEquals(before, zip.headers().get("etag"));
    }

    @Test
    void testEditManifestRefreshesContent() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        Response created = uploadSkill("/file-manifest", files);
        verify(created, 200);
        String before = created.headers().get("etag");

        String updated = """
                ---
                name: Renamed Skill
                description: New description
                version: 2.0.0
                ---
                # Renamed
                """;
        Response put = putSkillFile("/file-manifest", "SKILL.md", updated.getBytes(StandardCharsets.UTF_8));
        verify(put, 200);
        assertNotEquals(before, put.headers().get("etag"));
        assertEquals(updated, new String(getSkillFile("/file-manifest", "SKILL.md").body(), StandardCharsets.UTF_8));
    }

    @Test
    void testEditManifestInvalidFrontmatterRejected() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        Response created = uploadSkill("/file-manifest-bad", files);
        verify(created, 200);
        String before = created.headers().get("etag");

        // valid YAML but missing the mandatory description -> 400
        String bad = """
                ---
                name: Only Name
                ---
                """;
        verify(putSkillFile("/file-manifest-bad", "SKILL.md", bad.getBytes(StandardCharsets.UTF_8)), 400);

        // the skill is unchanged
        assertEquals(before, downloadSkill("/file-manifest-bad").headers().get("etag"));
        assertEquals(VALID_MANIFEST,
                new String(getSkillFile("/file-manifest-bad", "SKILL.md").body(), StandardCharsets.UTF_8));
    }

    @Test
    void testPutFileIfMatch() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        Response created = uploadSkill("/file-ifmatch", files);
        verify(created, 200);
        String etag = created.headers().get("etag");

        verify(putSkillFile("/file-ifmatch", "a.txt", "x".getBytes(StandardCharsets.UTF_8), "if-match", "\"wrong\""), 412);
        verify(putSkillFile("/file-ifmatch", "a.txt", "x".getBytes(StandardCharsets.UTF_8), "if-match", etag), 200);
    }

    @Test
    void testDeleteFileIfMatch() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        files.put("a.txt", "x".getBytes(StandardCharsets.UTF_8));
        Response created = uploadSkill("/file-del-ifmatch", files);
        verify(created, 200);
        String etag = created.headers().get("etag");

        verify(deleteSkillFile("/file-del-ifmatch", "a.txt", "if-match", "\"wrong\""), 412);
        verify(deleteSkillFile("/file-del-ifmatch", "a.txt", "if-match", etag), 200);
    }

    @Test
    void testSingleFileOnAbsentResource() {
        assertEquals(404, getSkillFile("/absent", "a.txt").status());
        verify(putSkillFile("/absent", "a.txt", "x".getBytes(StandardCharsets.UTF_8)), 404);
        verify(deleteSkillFile("/absent", "a.txt"), 404);
    }

    @Test
    void testSingleFileOnDeletedResource() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        verify(uploadSkill("/file-after-delete", files), 200);
        verify(deleteSkill("/file-after-delete"), 200);

        assertEquals(404, getSkillFile("/file-after-delete", "SKILL.md").status());
        verify(putSkillFile("/file-after-delete", "a.txt", "x".getBytes(StandardCharsets.UTF_8)), 404);
        verify(deleteSkillFile("/file-after-delete", "a.txt"), 404);
    }

    @Test
    void testMetadataListingClassifiesChildren() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        // deep PUT auto-vivifies the "cat" grouping folder
        verify(uploadSkill("/cat/skill-a", files), 200);
        verify(uploadSkill("/cat/skill-b", files), 200);
        verify(createFolder("/cat/sub/"), 200);

        // a DIAL resource is reported as ITEM, a grouping folder as FOLDER, classified from the marker file
        Response listing = listMetadata("cat");
        verify(listing, 200);
        Map<String, String> nodeTypes = childNodeTypes(listing);
        assertEquals(Set.of("skill-a", "skill-b", "sub"), nodeTypes.keySet());
        assertEquals("ITEM", nodeTypes.get("skill-a"));
        assertEquals("ITEM", nodeTypes.get("skill-b"));
        assertEquals("FOLDER", nodeTypes.get("sub"));
    }

    @Test
    void testMetadataListingRecursive() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        files.put("scripts/run.sh", "echo hi".getBytes(StandardCharsets.UTF_8));
        verify(uploadSkill("/tree/skill-a", files), 200);
        verify(uploadSkill("/tree/sub/nested", files), 200);

        // non-recursive: only the immediate children (a resource and a grouping folder)
        assertEquals(Set.of("skill-a", "sub"), childNodeTypes(listMetadata("tree")).keySet());

        // recursive: every DIAL resource/folder in the subtree, and never the files inside a resource
        Set<String> urls = childUrls(send(HttpMethod.GET,
                "/v2/metadata/skills/" + bucket + "/tree", "recursive=true", ""));
        assertTrue(urls.contains("skills/" + bucket + "/tree/skill-a/"), urls.toString());
        assertTrue(urls.contains("skills/" + bucket + "/tree/sub/"), urls.toString());
        assertTrue(urls.contains("skills/" + bucket + "/tree/sub/nested/"), urls.toString());
        // resource files (SKILL.md, scripts/run.sh) and the internal v/ version tree must be excluded
        assertTrue(urls.stream().noneMatch(u -> u.contains("SKILL.md") || u.contains("/v/") || u.contains("run.sh")),
                urls.toString());
    }

    @Test
    void testMetadataListingOmitsDeletedResources() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        verify(uploadSkill("/grp/keep", files), 200);
        verify(uploadSkill("/grp/gone", files), 200);
        verify(deleteSkill("/grp/gone"), 200);

        assertEquals(Set.of("keep"), childNodeTypes(listMetadata("grp")).keySet());
    }

    @Test
    void testMetadataFilesListing() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        files.put("scripts/run.sh", "echo hi".getBytes(StandardCharsets.UTF_8));
        verify(uploadSkill("/files-skill", files), 200);

        // non-recursive: immediate entries of the version, under a clean .../files/ url (version prefix hidden)
        Set<String> immediate = childUrls(listSkillFiles("/files-skill"));
        assertTrue(immediate.contains("skills/" + bucket + "/files-skill/files/SKILL.md"), immediate.toString());
        assertTrue(immediate.contains("skills/" + bucket + "/files-skill/files/scripts/"), immediate.toString());

        // recursive: all files flattened
        Set<String> all = childUrls(send(HttpMethod.GET,
                "/v2/metadata/skills/" + bucket + "/files-skill/files", "recursive=true", ""));
        assertTrue(all.contains("skills/" + bucket + "/files-skill/files/SKILL.md"), all.toString());
        assertTrue(all.contains("skills/" + bucket + "/files-skill/files/scripts/run.sh"), all.toString());

        // files listing of an absent resource -> 404
        assertEquals(404, listSkillFiles("/no-such-skill").status());
    }

    @Test
    void testAutoVivifyIntermediateFolders() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        verify(uploadSkill("/a/b/deep", files), 200);

        assertEquals("FOLDER", childNodeTypes(listMetadata("a")).get("b"));
        assertEquals("ITEM", childNodeTypes(listMetadata("a/b")).get("deep"));

        // a second deep PUT reuses the vivified folders
        verify(uploadSkill("/a/b/deep2", files), 200);
        assertEquals(Set.of("deep", "deep2"), childNodeTypes(listMetadata("a/b")).keySet());
    }

    @Test
    void testRejectResourceInsideResource() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        verify(uploadSkill("/outer", files), 200);
        // a resource must never be created inside another resource
        verify(uploadSkill("/outer/inner", files), 400);
    }

    @Test
    void testRejectNameCollisions() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));

        // skill exists -> folder create at the same path is rejected
        verify(uploadSkill("/collide-a", files), 200);
        verify(createFolder("/collide-a/"), 400);

        // folder exists -> skill PUT at the same path is rejected
        verify(createFolder("/collide-b/"), 200);
        verify(uploadSkill("/collide-b", files), 400);

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
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        verify(uploadSkill("/parent/child", files), 200);

        // the folder holds a child resource -> 409, and the child is untouched (no cascade)
        verify(deleteFolder("/parent/"), 409);
        assertEquals(200, downloadSkill("/parent/child").status());
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
        assertEquals(400, downloadSkill("/as-folder").status());
        // trailing-slash GET -> 400
        assertEquals(400, send(HttpMethod.GET, "/v2/skills/" + bucket + "/as-folder/", null, "").status());
    }

    @Test
    void testOwnerAccessAndDenialForOtherUser() {
        Map<String, byte[]> files = Map.of("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        // owner (default user) can create and read the skill by bucket-location match, no marker walk needed
        Response created = uploadSkill("/private-skill", files);
        verify(created, 200);
        assertEquals(200, downloadSkill("/private-skill").status());

        // another user has no access: whole-resource, single-file and metadata ops are all forbidden, not merely "not found"
        assertEquals(403, downloadSkill("/private-skill", "Api-key", "proxyKey2").status());
        assertEquals(403, getSkillFile("/private-skill", "SKILL.md", "Api-key", "proxyKey2").status());
        verify(listMetadata("private-skill", "Api-key", "proxyKey2"), 403);
        verify(uploadSkill("/private-skill", files, "Api-key", "proxyKey2"), 403);
        verify(putSkillFile("/private-skill", "a.txt", "x".getBytes(StandardCharsets.UTF_8), "Api-key", "proxyKey2"), 403);
        verify(deleteSkill("/private-skill", "Api-key", "proxyKey2"), 403);
    }

    @Test
    void testFolderShareInheritsFileAccess() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));
        files.put("scripts/run.sh", "echo hi".getBytes(StandardCharsets.UTF_8));
        verify(uploadSkill("/shared-group/skill-a", files), 200);

        // before sharing, the other user has no access
        assertEquals(403, downloadSkill("/shared-group/skill-a", "Api-key", "proxyKey2").status());

        // share the grouping folder (not the skill itself)
        Response share = operationRequest("/v1/ops/resource/share/create", """
                {
                  "invitationType": "link",
                  "resources": [
                    { "url": "skills/%s/shared-group/" }
                  ]
                }
                """.formatted(bucket));
        verify(share, 200);
        InvitationLink invitationLink = ProxyUtil.convertToObject(share.body(), InvitationLink.class);
        assertNotNull(invitationLink);

        verify(send(HttpMethod.GET, invitationLink.invitationLink(), "accept=true", null, "Api-key", "proxyKey2"), 200);

        // access to the skill, to a file beneath it, and to the folder's metadata listing is now inherited
        // from the folder share, via the same recursive parent-permission lookup used for v1 resources
        assertEquals(200, downloadSkill("/shared-group/skill-a", "Api-key", "proxyKey2").status());
        assertEquals("echo hi", new String(
                getSkillFile("/shared-group/skill-a", "scripts/run.sh", "Api-key", "proxyKey2").body(), StandardCharsets.UTF_8));
        verify(listMetadata("shared-group", "Api-key", "proxyKey2"), 200);
    }

    private Response listMetadata(String relativePath, String... headers) {
        return send(HttpMethod.GET, "/v2/metadata/skills/" + bucket + "/" + relativePath, null, "", headers);
    }

    private Response listSkillFiles(String skillPath, String... headers) {
        return send(HttpMethod.GET, "/v2/metadata/skills/" + bucket + skillPath + "/files", null, "", headers);
    }

    private Response createFolder(String folderPath, String... headers) {
        return send(HttpMethod.PUT, "/v2/skills/" + bucket + folderPath, null, "", headers);
    }

    private Response deleteFolder(String folderPath, String... headers) {
        return send(HttpMethod.DELETE, "/v2/skills/" + bucket + folderPath, null, "", headers);
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
    private Response uploadSkill(String skillPath, Map<String, byte[]> files, String... headers) {
        String uri = "http://127.0.0.1:" + serverPort + "/v2/skills/" + bucket + skillPath;
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
    private Response deleteSkill(String skillPath, String... headers) {
        String uri = "http://127.0.0.1:" + serverPort + "/v2/skills/" + bucket + skillPath;
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
    private BinaryResponse downloadSkill(String skillPath, String... headers) {
        String uri = "http://127.0.0.1:" + serverPort + "/v2/skills/" + bucket + skillPath;
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
    private Response putSkillFile(String skillPath, String filePath, byte[] content, String... headers) {
        String uri = "http://127.0.0.1:" + serverPort + "/v2/skills/" + bucket + skillPath + "/files/" + filePath;
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
    private Response deleteSkillFile(String skillPath, String filePath, String... headers) {
        String uri = "http://127.0.0.1:" + serverPort + "/v2/skills/" + bucket + skillPath + "/files/" + filePath;
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
    private BinaryResponse getSkillFile(String skillPath, String filePath, String... headers) {
        String uri = "http://127.0.0.1:" + serverPort + "/v2/skills/" + bucket + skillPath + "/files/" + filePath;
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
                name: My Skill
                description: Does something useful
                ---
                """;

        @Override
        protected JsonObject additionalSettingsOverrides() {
            return new JsonObject("""
                    {
                      "complexResource": {
                        "maxFiles": 3,
                        "maxTotalBytes": 200,
                        "maxFileSizeBytes": 100
                      }
                    }
                    """);
        }

        @Test
        void testWholeResourcePutRejectsExceedingMaxFiles() {
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("SKILL.md", MANIFEST.getBytes(StandardCharsets.UTF_8));
            files.put("a.txt", "a".getBytes(StandardCharsets.UTF_8));
            files.put("b.txt", "b".getBytes(StandardCharsets.UTF_8));
            files.put("c.txt", "c".getBytes(StandardCharsets.UTF_8));

            verify(uploadSkill("/too-many-files", files), 400);
            assertEquals(404, downloadSkill("/too-many-files").status());
        }

        @Test
        void testWholeResourcePutAcceptsExactlyMaxFiles() {
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("SKILL.md", MANIFEST.getBytes(StandardCharsets.UTF_8));
            files.put("a.txt", "a".getBytes(StandardCharsets.UTF_8));
            files.put("b.txt", "b".getBytes(StandardCharsets.UTF_8));

            verify(uploadSkill("/exact-max-files", files), 200);
        }

        @Test
        void testWholeResourcePutRejectsExceedingMaxTotalBytes() {
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("SKILL.md", MANIFEST.getBytes(StandardCharsets.UTF_8));
            files.put("big.txt", "x".repeat(200).getBytes(StandardCharsets.UTF_8));

            Response put = uploadSkill("/too-big-total", files);
            verify(put, 413);
            assertEquals(404, downloadSkill("/too-big-total").status());
        }

        @Test
        void testWholeResourcePutRejectsExceedingMaxFileSize() {
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("SKILL.md", MANIFEST.getBytes(StandardCharsets.UTF_8));
            files.put("huge.txt", "x".repeat(150).getBytes(StandardCharsets.UTF_8));

            Response put = uploadSkill("/too-big-file", files);
            verify(put, 413);
            assertEquals(404, downloadSkill("/too-big-file").status());
        }

        @Test
        void testSingleFilePutRejectsExceedingMaxFileSize() {
            Map<String, byte[]> files = Map.of("SKILL.md", MANIFEST.getBytes(StandardCharsets.UTF_8));
            verify(uploadSkill("/file-too-big", files), 200);

            verify(putSkillFile("/file-too-big", "big.txt", "x".repeat(150).getBytes(StandardCharsets.UTF_8)), 413);
            // rejected mutation left nothing observable: the file was never added
            assertEquals(404, getSkillFile("/file-too-big", "big.txt").status());
        }

        @Test
        void testSingleFilePutRejectsExceedingMaxTotalBytes() {
            Map<String, byte[]> files = Map.of("SKILL.md", MANIFEST.getBytes(StandardCharsets.UTF_8));
            Response created = uploadSkill("/file-total-too-big", files);
            verify(created, 200);
            String before = created.headers().get("etag");

            // SKILL.md is small; adding a near-limit file tips the aggregate past maxTotalBytes(200)
            verify(putSkillFile("/file-total-too-big", "big.txt", "x".repeat(190).getBytes(StandardCharsets.UTF_8)), 413);

            // rejected mutation must not have changed the resource
            assertEquals(before, downloadSkill("/file-total-too-big").headers().get("etag"));
        }

        @Test
        void testSingleFilePutRejectsExceedingMaxFiles() {
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("SKILL.md", MANIFEST.getBytes(StandardCharsets.UTF_8));
            files.put("a.txt", "a".getBytes(StandardCharsets.UTF_8));
            files.put("b.txt", "b".getBytes(StandardCharsets.UTF_8));
            verify(uploadSkill("/file-count-too-big", files), 200);

            // adding a 4th distinct file exceeds maxFiles(3)
            verify(putSkillFile("/file-count-too-big", "c.txt", "c".getBytes(StandardCharsets.UTF_8)), 400);

            // replacing an existing file is fine (file count unchanged)
            verify(putSkillFile("/file-count-too-big", "a.txt", "aa".getBytes(StandardCharsets.UTF_8)), 200);
        }

        @SneakyThrows
        private Response uploadSkill(String skillPath, Map<String, byte[]> files, String... headers) {
            String uri = "http://127.0.0.1:" + serverPort + "/v2/skills/" + bucket + skillPath;
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
        private BinaryResponse downloadSkill(String skillPath, String... headers) {
            String uri = "http://127.0.0.1:" + serverPort + "/v2/skills/" + bucket + skillPath;
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
        private Response putSkillFile(String skillPath, String filePath, byte[] content, String... headers) {
            String uri = "http://127.0.0.1:" + serverPort + "/v2/skills/" + bucket + skillPath + "/files/" + filePath;
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
        private BinaryResponse getSkillFile(String skillPath, String filePath, String... headers) {
            String uri = "http://127.0.0.1:" + serverPort + "/v2/skills/" + bucket + skillPath + "/files/" + filePath;
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
