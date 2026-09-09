package com.epam.aidial.core.storage.resource;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the physical paths {@link ResourceDescriptor} composes, so that routing them through a
 * {@link StorageLayout} stays behaviour-preserving.
 */
public class ResourceDescriptorPathTest {

    @Test
    public void testAbsoluteFilePathOfFile() {
        ResourceDescriptor file = new ResourceDescriptor(ResourceTypes.FILE, "notes.txt",
                List.of("documents"), "bucket", "Users/u1/", false);

        assertEquals("Users/u1/files/documents/notes.txt", file.getAbsoluteFilePath());
    }

    @Test
    public void testAbsoluteFilePathOfFolder() {
        ResourceDescriptor folder = new ResourceDescriptor(ResourceTypes.FILE, "documents",
                List.of(), "bucket", "Users/u1/", true);

        assertEquals("Users/u1/files/documents/", folder.getAbsoluteFilePath());
    }

    @Test
    public void testAbsoluteFilePathOfRootFolder() {
        ResourceDescriptor root = new ResourceDescriptor(ResourceTypes.FILE, null,
                List.of(), "bucket", "public/", true);

        assertEquals("public/files/", root.getAbsoluteFilePath());
    }

    @Test
    public void testAbsoluteFilePathUsesResourceTypeGroup() {
        ResourceDescriptor application = new ResourceDescriptor(ResourceTypes.APP_TYPE_SCHEMA, "schema",
                List.of(), "bucket", "platform/", false);

        assertEquals("platform/app_type_schemas/schema", application.getAbsoluteFilePath());
    }

    @Test
    public void testResolveByPath() {
        ResourceDescriptor folder = new ResourceDescriptor(ResourceTypes.CONVERSATION, null,
                List.of(), "bucket", "Users/u1/", true);

        ResourceDescriptor resolved = folder.resolveByPath("Users/u1/conversations/chats/chat1");

        assertEquals("chat1", resolved.getName());
        assertEquals(List.of("chats"), resolved.getParentFolders());
        assertEquals("Users/u1/conversations/chats/chat1", resolved.getAbsoluteFilePath());
    }

    @Test
    public void testRelativePathWithinFolder() {
        ResourceDescriptor folder = new ResourceDescriptor(ResourceTypes.FILE, "documents",
                List.of(), "bucket", "Users/u1/", true);
        ResourceDescriptor file = new ResourceDescriptor(ResourceTypes.FILE, "notes.txt",
                List.of("documents"), "bucket", "Users/u1/", false);

        assertEquals("notes.txt", folder.getRelativePath(file));
    }
}
