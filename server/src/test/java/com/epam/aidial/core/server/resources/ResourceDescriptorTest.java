package com.epam.aidial.core.server.resources;

import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ResourceDescriptorTest {

    static Stream<Arguments> hiddenFolderTestCases() {
        return Stream.of(
                Arguments.of(".quick_app_name_0.0.1", true),
                Arguments.of(".mind_map_name_0.0.2", true),
                Arguments.of(".hidden_folder", true),
                Arguments.of("normal_folder", false),
                Arguments.of("folder.with.dots", false),
                Arguments.of("", false),
                Arguments.of("folder_starting_with_dot.", false)
        );
    }

    @ParameterizedTest
    @MethodSource("hiddenFolderTestCases")
    void shouldIdentifyHiddenFolders(String folderName, boolean expectedResult) {
        ResourceDescriptor resource = new ResourceDescriptor(
                ResourceTypes.FILE,
                folderName,
                List.of(),
                "bucket",
                "public/",
                false
        );

        assertEquals(expectedResult, resource.isHidden());
    }

    static Stream<Arguments> filesInHiddenFoldersTestCases() {
        return Stream.of(
                Arguments.of(List.of(".quick_app_name_0.0.1"), true),
                Arguments.of(List.of("normal_folder", ".hidden_subfolder"), true),
                Arguments.of(List.of(".hidden", "subfolder", "nested"), true),
                Arguments.of(List.of("normal_folder"), false),
                Arguments.of(List.of("folder1", "folder2", "folder3"), false),
                Arguments.of(List.of(), false)
        );
    }

    @ParameterizedTest
    @MethodSource("filesInHiddenFoldersTestCases")
    void shouldIdentifyFilesInHiddenFolders(List<String> parentFolders, boolean expectedResult) {
        ResourceDescriptor resource = new ResourceDescriptor(
                ResourceTypes.FILE,
                "document.json",
                parentFolders,
                "bucket",
                "public/",
                false
        );

        assertEquals(expectedResult, resource.isHidden());
    }

    @Test
    public void testIsPublic() {
        // code-app case
        ResourceDescriptor resource1 = new ResourceDescriptor(
                ResourceTypes.FILE,
                "document.json",
                List.of(),
                "bucket",
                "public/deployments/0320c9e97aef4dac898a1d65f06af7d1/",
                false
        );
        assertTrue(resource1.isPublic());
        // regular file
        ResourceDescriptor resource2 = new ResourceDescriptor(
                ResourceTypes.FILE,
                "document.json",
                List.of(),
                "bucket",
                "public/",
                false
        );
        assertTrue(resource2.isPublic());
        // private resource
        // regular file
        ResourceDescriptor resource3 = new ResourceDescriptor(
                ResourceTypes.FILE,
                "document.json",
                List.of(),
                "bucket",
                "<user_sub>/",
                false
        );
        assertFalse(resource3.isPublic());
    }

}
