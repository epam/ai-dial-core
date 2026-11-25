package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.ShortNameResolver.ClassifiedReference;
import com.epam.aidial.core.server.service.ShortNameResolver.ReferenceForm;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ShortNameResolverClassificationTest {

    @Mock
    private EncryptionService encryptionService;

    private ClassifiedReference classify(String value) {
        return ShortNameResolver.classify(value, encryptionService);
    }

    private void assertForm(ReferenceForm expected, String value) {
        ClassifiedReference result = classify(value);
        assertEquals(expected, result.form(), value);
        if (expected != ReferenceForm.RESOURCE_URL) {
            assertNull(result.descriptor(), "only a resource URL carries a descriptor");
        }
    }

    private static Stream<Arguments> resourceUrls() {
        return Stream.of(
                Arguments.of("toolsets/platform/t", ResourceTypes.TOOL_SET, "platform/", "t"),
                Arguments.of("applications/public/folder/a__1.0.0", ResourceTypes.APPLICATION, "public/", "a__1.0.0"));
    }

    @ParameterizedTest
    @MethodSource("resourceUrls")
    public void testResourceUrlCarriesItsDescriptor(String value, ResourceTypes type, String bucketLocation, String name) {
        ClassifiedReference result = classify(value);

        assertEquals(ReferenceForm.RESOURCE_URL, result.form());
        assertEquals(type, result.descriptor().getType());
        assertEquals(bucketLocation, result.descriptor().getBucketLocation());
        assertEquals(name, result.descriptor().getName());
    }

    @Test
    public void testResourceUrlOfAnEncryptedBucketIsDecryptedThroughTheEncryptionService() {
        when(encryptionService.decrypt("bucketToken")).thenReturn("Users/someone/");

        ClassifiedReference result = classify("files/bucketToken/folder/file.txt");

        assertEquals(ReferenceForm.RESOURCE_URL, result.form());
        assertEquals("Users/someone/", result.descriptor().getBucketLocation());
    }

    /**
     * The last value has a space in its bucket segment: {@code UrlUtil.decodePath} rethrows the {@code URISyntaxException} as a
     * {@code RuntimeException}, which is not an {@code IllegalArgumentException} and is still malformed.
     */
    @ParameterizedTest
    @ValueSource(strings = {"files/b/a b", "toolsets/platform", "/toolsets/platform/t", "unknown-type/platform/x", "a/b",
            "files/badToken/file.txt", "toolsets/pla tform/t"})
    public void testValueThatIsNotResourceUrlIsMalformed(String value) {
        // a bucket that cannot be decrypted
        lenient().when(encryptionService.decrypt("badToken")).thenThrow(new IllegalArgumentException("not a bucket"));

        assertForm(ReferenceForm.MALFORMED, value);
    }

    @Test
    public void testEmptyDotAndDotDotSegmentsAreMalformedWhetherEncodedOrNot() {
        for (String value : new String[] {
                "files/public/a/../b", "files/public/a/%2e%2e/b", "files/public/a/%2E%2E/b", "files/public/a/%2e./b", "files/public/a/./b",
                "files/public/a/%2e/b", "files/public/../b", "files/public/a//b", "files/public/a/b/..", "files/public/a/b/%2e%2e/",
                "applications/public/a/../b", "applications/platform/%2e%2e", "toolsets/public/a/./t"}) {
            assertForm(ReferenceForm.MALFORMED, value);
        }
    }

    @Test
    public void testNamesThatOnlyContainDotsAreNotMistakenForTraversal() {
        for (String value : new String[] {"files/public/a/..b", "files/public/a/b..", "files/public/a/.b/c", "files/public/a.b/c...d",
                "files/public/a/%2e%2eb", "files/public/a/b/"}) {
            assertForm(ReferenceForm.RESOURCE_URL, value);
        }
    }
}
