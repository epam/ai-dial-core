package com.epam.aidial.core.server;

import com.epam.aidial.core.server.ExportApiSupport.Reply;
import com.epam.aidial.core.server.service.ExportArchive;
import com.epam.aidial.core.server.service.resource.SkillHandler;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.EtagHeader;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The export of the Skills of an Application through the real server: a public Skill named by a stored {@code dependencies} entry is
 * exported as the files of its current version, in a logical folder below the path of the Skill, copied as stored and never examined.
 */
public class ExportSkillApiTest extends ResourceBaseTest {

    private static final String MANIFEST = "---\nname: My Skill\ndescription: Does something useful\nversion: 1.0.0\n---\n# My Skill\n\nBody.\n";

    private Reply post(String body) throws Exception {
        return ExportApiSupport.post(serverPort, body);
    }

    private static ResourceDescriptor skill(String path) {
        return ResourceDescriptorFactory.fromDecoded(ResourceTypes.SKILL, "public", "public/", path);
    }

    private void putSkill(String path, Map<String, String> files) {
        Map<String, Buffer> uploads = new LinkedHashMap<>();
        files.forEach((name, content) -> uploads.put(name, Buffer.buffer(content)));
        dial.getProxy().getComplexResourceService().put(skill(path), new SkillHandler(), uploads, EtagHeader.ANY, "author");
    }

    private void storeRoot(String dependencies) {
        dial.getStorage().store("platform/applications/exported", "application/json", null, Map.of(), ("{\"endpoint\":\"http://application.invalid/chat\","
                + "\"dependencies\":" + dependencies + "}").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void testSkillIsExportedAsTheFilesOfItsCurrentVersionInLogicalFolder() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", MANIFEST);
        files.put("scripts/run.py", "print('hello')\n");
        // looks like JSON and holds an ENC[ value: it must be copied as it is, neither parsed nor redacted
        files.put("data/config.json", "{\"key\":\"ENC[abc]\"}");
        // names resources, but the content of a Skill is never examined for references
        files.put("notes.md", "see applications/platform/other and toolsets/public/t\n");
        putSkill("s", files);
        dial.getStorage().store("platform/applications/other", "application/json", null, Map.of(),
                "{\"endpoint\":\"http://other.invalid/chat\"}".getBytes(StandardCharsets.UTF_8));
        storeRoot("[\"skills/public/s\"]");

        Reply reply = post(ExportApiSupport.rootsRequest("applications/platform/exported"));

        assertEquals(200, reply.status(), reply.text());
        Map<String, byte[]> entries = reply.entries();
        for (Map.Entry<String, String> file : files.entrySet()) {
            assertArrayEquals(file.getValue().getBytes(StandardCharsets.UTF_8), entries.get("public/skills/s/" + file.getKey()), file.getKey());
        }
        List<String> expected = new ArrayList<>(List.of(ExportArchive.MANIFEST_ENTRY, "platform/applications/exported"));
        new TreeMap<>(files).keySet().forEach(path -> expected.add("public/skills/s/" + path));
        assertEquals(expected, List.copyOf(entries.keySet()), "the Application and one entry for each file of the Skill in the order of the paths, "
                + "and neither the marker, the version folder nor anything that the content of the Skill names");
    }
}
