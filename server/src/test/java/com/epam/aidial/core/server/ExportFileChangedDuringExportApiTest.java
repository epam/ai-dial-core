package com.epam.aidial.core.server;

import com.epam.aidial.core.server.service.ExportArchive;
import com.epam.aidial.core.server.service.resource.SkillHandler;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A File or Skill that changes while the export is being written, through the real server: the change is listed in the verification
 * entry, the version that was written is whole, and a File that is gone resets the response. The root names four Files of 700 KB
 * each, which are written first and fill the pipe, and a small File that is written last. The client is paused after the response
 * head, so that the last File is changed after the export has planned it and before it is copied. The Files are below the cache
 * size of the resource service, so that they can be deleted by the teardown on Windows.
 */
public class ExportFileChangedDuringExportApiTest extends ResourceBaseTest {

    private static final String PATH = "/v1/admin/export";
    private static final int FILLERS = 4;
    private static final int FILLER_BYTES = 700_000;
    private static final String REQUEST = "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/file-root\"}]}";
    private static final String SKILL_REQUEST = "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/skill-root\"}]}";
    private static final String MANIFEST = "---\nname: Changing\ndescription: A skill that changes during the export\nversion: 1.0.0\n---\n# Changing\n";
    private static final byte[] TAIL = bytes(100, 1);
    private static final String TAIL_ENTRY = "public/files/icons/tail.png";

    @Override
    protected JsonObject additionalSettingsOverrides() {
        return new JsonObject().put("adminExport", new JsonObject().put("stallTimeout", 30000));
    }

    private static byte[] bytes(int length, long seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static ResourceDescriptor file(String path) {
        return ResourceDescriptorFactory.fromDecoded(ResourceTypes.FILE, "public", "public/", path);
    }

    private void putSkill(String manifest) {
        dial.getProxy().getComplexResourceService().put(ResourceDescriptorFactory.fromDecoded(ResourceTypes.SKILL, "public", "public/", "changing"),
                new SkillHandler(), Map.of("SKILL.md", Buffer.buffer(manifest)), EtagHeader.ANY, "author");
    }

    private void putFile(String path, byte[] content) {
        dial.getProxy().getResourceService().putFile(file(path), content, EtagHeader.ANY, "application/octet-stream", null);
    }

    @BeforeEach
    void storePackage() {
        dial.getProxy().setExportBodyRedactor((identity, body) -> body.getBytes(StandardCharsets.UTF_8));
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < FILLERS; i++) {
            putFile("filler/" + i + ".bin", bytes(FILLER_BYTES, 10 + i));
            urls.add("\"files/public/filler/" + i + ".bin\"");
        }
        putFile("icons/tail.png", TAIL);
        dial.getStorage().store("platform/applications/file-root", "application/json", null, Map.of(), ("{\"endpoint\":\"http://root.invalid/chat\","
                + "\"icon_url\":\"files/public/icons/tail.png\",\"dependencies\":[" + String.join(",", urls) + "]}").getBytes(StandardCharsets.UTF_8));
        putSkill(MANIFEST);
        List<String> skillUrls = new ArrayList<>(urls);
        skillUrls.add("\"skills/public/changing\"");
        dial.getStorage().store("platform/applications/skill-root", "application/json", null, Map.of(), ("{\"endpoint\":\"http://root.invalid/chat\","
                + "\"dependencies\":[" + String.join(",", skillUrls) + "]}").getBytes(StandardCharsets.UTF_8));
    }

    private record Stream(HttpClientRequest request, HttpClientResponse response, Buffer received, Future<Void> finished) {
    }

    /** Sends the request and returns when the response head has arrived, with the body paused. */
    private Stream connect(String body) throws Exception {
        RequestOptions options = new RequestOptions().setAbsoluteURI("http://127.0.0.1:" + serverPort + PATH).setMethod(HttpMethod.POST)
                .putHeader("authorization", "admin");
        return await(dial.getClient().request(options).compose(request -> request.send(Buffer.buffer(body)).map(response -> {
            response.pause();
            Buffer received = Buffer.buffer();
            Promise<Void> finished = Promise.promise();
            response.handler(received::appendBuffer);
            response.endHandler(ignored -> finished.tryComplete());
            response.exceptionHandler(finished::tryFail);
            return new Stream(request, response, received, finished.future());
        })));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(120, TimeUnit.SECONDS);
    }

    private Stream connectAndChange(Runnable change) throws Exception {
        return connectAndChange(REQUEST, change);
    }

    private Stream connectAndChange(String body, Runnable change) throws Exception {
        Stream stream = connect(body);
        assertEquals(200, stream.response().statusCode());
        change.run();
        stream.response().resume();
        return stream;
    }

    private static Map<String, byte[]> entries(Buffer buffer) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(buffer.getBytes()))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private static String latin(Buffer buffer) {
        return new String(buffer.getBytes(), StandardCharsets.ISO_8859_1);
    }

    @Test
    void testFileReplacedByNewerCompleteVersionAfterThePlanIsWrittenWholeAndListedAsChanged() throws Exception {
        byte[] newer = bytes(150, 2);
        Stream stream = connectAndChange(() -> putFile("icons/tail.png", newer));

        await(stream.finished());

        Map<String, byte[]> entries = entries(stream.received());
        assertArrayEquals(newer, entries.get(TAIL_ENTRY), "the newer version is written whole");
        JsonNode change = ProxyUtil.MAPPER.readTree(entries.get(ExportArchive.VERIFICATION_ENTRY)).get("changedDuringExport").get(0);
        assertEquals(TAIL_ENTRY, change.get("name").asText());
        assertTrue(change.has("etagBefore") && change.has("etagAfter"));
    }

    @Test
    void testFileThatIsGoneAfterThePlanResetsTheResponseWithNoCentralDirectory() throws Exception {
        Stream stream = connectAndChange(() -> dial.getProxy().getResourceService().deleteResource(file("icons/tail.png"), EtagHeader.ANY));

        assertThrows(ExecutionException.class, () -> await(stream.finished()), "the response is reset, never ended cleanly");
        String partial = latin(stream.received());
        assertTrue(partial.startsWith("PK\u0003\u0004"), "what arrived is the beginning of a ZIP");
        assertFalse(partial.contains("PK\u0005\u0006"), "no end of central directory arrived");
        assertFalse(partial.contains(ExportArchive.VERIFICATION_ENTRY), "the verification entry was not reached");
    }

    @Test
    void testSkillThatIsReplacedAfterThePlanIsExportedAsThePinnedVersionAndListedAsChanged() throws Exception {
        // a replaced version is not removed at once: a sweep removes it after a grace period, so the version that the plan pinned is still whole
        Stream stream = connectAndChange(SKILL_REQUEST, () -> putSkill(MANIFEST + "CHANGED-AFTER-THE-PLAN" + "\n"));

        await(stream.finished());

        Map<String, byte[]> entries = entries(stream.received());
        assertArrayEquals(MANIFEST.getBytes(StandardCharsets.UTF_8), entries.get("public/skills/changing/SKILL.md"), "the pinned version is written, not the new one");
        JsonNode change = ProxyUtil.MAPPER.readTree(entries.get(ExportArchive.VERIFICATION_ENTRY)).get("changedDuringExport").get(0);
        assertEquals("public/skills/changing", change.get("name").asText());
        assertTrue(change.has("etagBefore") && change.has("etagAfter"));
    }
}
