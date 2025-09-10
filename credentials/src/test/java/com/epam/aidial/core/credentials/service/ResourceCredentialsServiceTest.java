package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.credentials.data.credentials.CredentialBucketLocation;
import com.epam.aidial.core.credentials.data.credentials.CredentialsDescriptor;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.data.credentials.ResourceTypes;
import com.epam.aidial.core.credentials.data.credentials.SourceType;
import com.epam.aidial.core.credentials.encryption.ContentEncryptionKeyService;
import com.epam.aidial.core.credentials.encryption.CredentialsEncryptionService;
import com.epam.aidial.core.credentials.util.JsonMapperUtil;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceCredentialsServiceTest {

    private static final String TOOL_SET_NAME = "toolsets/bucket-name/folder1/my-toolset";
    private static final byte[] CEK = "test_cek".getBytes();
    private static final byte[] OLD_ENCRYPTED_BODY = "old_encrypted_body".getBytes();
    private static final byte[] ENCRYPTED_BODY = "encrypted_body".getBytes();

    @Mock
    private ResourceService resourceService;
    @Mock
    private ContentEncryptionKeyService contentEncryptionKeyService;
    @Mock
    private CredentialsEncryptionService credentialsEncryptionService;

    @InjectMocks
    private ResourceCredentialsService service;

    @Test
    public void testAddResourceCredentials_putsResource() {
        ResourceCredentials creds = createCredentials(CredentialsLevel.USER);
        CredentialsDescriptor descriptor = createCredentialsDescriptor();
        when(contentEncryptionKeyService.getOrCreateKey(any())).thenReturn(CEK);
        when(credentialsEncryptionService.encrypt(any(), any(), any())).thenReturn(ENCRYPTED_BODY);

        service.addResourceCredentials(descriptor, creds);

        ArgumentCaptor<ResourceDescriptor> descriptorCaptor = ArgumentCaptor.forClass(ResourceDescriptor.class);
        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<EtagHeader> etagCaptor = ArgumentCaptor.forClass(EtagHeader.class);

        verify(resourceService).putResourceBytes(descriptorCaptor.capture(), bodyCaptor.capture(), etagCaptor.capture());

        ResourceDescriptor passed = descriptorCaptor.getValue();
        Assertions.assertEquals(ResourceTypes.CREDENTIALS, passed.getType());
        assertEquals("my-toolset", passed.getName());
        assertEquals(List.of("credentials", "storage", "folder1"), passed.getParentFolders());
        assertEquals("bucket-name", passed.getBucketName());
        assertEquals("bucket-location/", passed.getBucketLocation());

        assertEquals(EtagHeader.ANY, etagCaptor.getValue());

        byte[] actualBody = bodyCaptor.getValue();
        assertEquals(ENCRYPTED_BODY, actualBody);
    }

    @Test
    public void testGetAllResourceCredentials_returnsOne() {
        ResourceCredentials creds = createCredentials(CredentialsLevel.USER);
        byte[] body = JsonMapperUtil.convertToString(creds).getBytes(StandardCharsets.UTF_8);
        CredentialsLocator credentialsLocator = createCredentialsLocator();

        when(resourceService.getResourceBytes(any(ResourceDescriptor.class))).thenReturn(ENCRYPTED_BODY);
        when(contentEncryptionKeyService.getKey(any())).thenReturn(CEK);
        when(credentialsEncryptionService.decrypt(any(), any(), any())).thenReturn(body);

        List<ResourceCredentials> list = service.getAllResourceCredentials(credentialsLocator);

        assertNotNull(list);
        assertEquals(1, list.size());
        ResourceCredentials c = list.getFirst();
        assertEquals(CredentialsLevel.USER, c.getCredentialsLevel());
        assertEquals(TOOL_SET_NAME, c.getResourceId());

        ArgumentCaptor<ResourceDescriptor> descriptorCaptor = ArgumentCaptor.forClass(ResourceDescriptor.class);
        verify(resourceService).getResourceBytes(descriptorCaptor.capture());

        ResourceDescriptor passed = descriptorCaptor.getValue();
        Assertions.assertEquals(ResourceTypes.CREDENTIALS, passed.getType());
        assertEquals("my-toolset", passed.getName());
        assertEquals(List.of("credentials", "storage", "folder1"), passed.getParentFolders());
        assertEquals("bucket-name", passed.getBucketName());
        assertEquals("bucket-location/", passed.getBucketLocation());
    }

    @Test
    public void testGetAllResourceCredentials_returnsEmptyWhenNull() {
        CredentialsLocator credentialsLocator = createCredentialsLocator();
        when(resourceService.getResourceBytes(any(ResourceDescriptor.class))).thenReturn(null);

        List<ResourceCredentials> list = service.getAllResourceCredentials(credentialsLocator);

        assertNotNull(list);
        assertTrue(list.isEmpty());
    }

    @Test
    public void testUpdateAllResourceCredentials_multipleEntriesWithSameLevel_throws() {
        CredentialsLocator credentialsLocator = createCredentialsLocator();
        ResourceCredentials c1 = createCredentials(CredentialsLevel.USER);
        ResourceCredentials c2 = createCredentials(CredentialsLevel.USER);

        assertThrows(IllegalStateException.class, () -> service.updateAllResourceCredentials(credentialsLocator, List.of(c1, c2)));
        verifyNoInteractions(resourceService);
    }

    @Test
    public void testUpdateAllResourceCredentials_notFound_throws() {
        CredentialsLocator credentialsLocator = createCredentialsLocator();
        ResourceCredentials c1 = createCredentials(CredentialsLevel.USER);

        doAnswer(inv -> {
            Function<String, String> mapper = inv.getArgument(1);
            mapper.apply(null); // should cause ResourceNotFoundException in service logic
            return null;
        }).when(resourceService).computeResourceBytes(any(ResourceDescriptor.class), any());

        assertThrows(ResourceNotFoundException.class, () -> service.updateAllResourceCredentials(credentialsLocator, List.of(c1)));

        ArgumentCaptor<ResourceDescriptor> descriptorCaptor = ArgumentCaptor.forClass(ResourceDescriptor.class);
        verify(resourceService).computeResourceBytes(descriptorCaptor.capture(), any());
        ResourceDescriptor passed = descriptorCaptor.getValue();
        Assertions.assertEquals(ResourceTypes.CREDENTIALS, passed.getType());
        assertEquals("my-toolset", passed.getName());
        assertEquals(List.of("credentials", "storage", "folder1"), passed.getParentFolders());
        assertEquals("bucket-name", passed.getBucketName());
        assertEquals("bucket-location/", passed.getBucketLocation());
    }

    @Test
    public void testUpdateAllResourceCredentials_deleteWhenEmptyList() {
        CredentialsLocator credentialsLocator = createCredentialsLocator();

        service.updateAllResourceCredentials(credentialsLocator, List.of());

        ArgumentCaptor<ResourceDescriptor> descriptorCaptor = ArgumentCaptor.forClass(ResourceDescriptor.class);
        ArgumentCaptor<Function<byte[], byte[]>> fnCaptor = ArgumentCaptor.forClass(Function.class);
        verify(resourceService).computeResourceBytes(descriptorCaptor.capture(), fnCaptor.capture());

        ResourceDescriptor passed = descriptorCaptor.getValue();
        Assertions.assertEquals(ResourceTypes.CREDENTIALS, passed.getType());
        assertEquals("my-toolset", passed.getName());
        assertEquals(List.of("credentials", "storage", "folder1"), passed.getParentFolders());
        assertEquals("bucket-name", passed.getBucketName());
        assertEquals("bucket-location/", passed.getBucketLocation());

        byte[] result = fnCaptor.getValue().apply(OLD_ENCRYPTED_BODY);
        assertNull(result);
    }

    @Test
    public void testUpdateAllResourceCredentials_updatesBody() {
        CredentialsLocator credentialsLocator = createCredentialsLocator();
        ResourceCredentials c1 = createCredentials(CredentialsLevel.USER);
        when(contentEncryptionKeyService.getOrCreateKey(any())).thenReturn(CEK);
        when(credentialsEncryptionService.encrypt(any(), any(), any())).thenReturn(ENCRYPTED_BODY);

        service.updateAllResourceCredentials(credentialsLocator, List.of(c1));

        ArgumentCaptor<ResourceDescriptor> descriptorCaptor = ArgumentCaptor.forClass(ResourceDescriptor.class);
        ArgumentCaptor<Function<byte[], byte[]>> fnCaptor = ArgumentCaptor.forClass(Function.class);
        verify(resourceService).computeResourceBytes(descriptorCaptor.capture(), fnCaptor.capture());

        ResourceDescriptor passed = descriptorCaptor.getValue();
        Assertions.assertEquals(ResourceTypes.CREDENTIALS, passed.getType());
        assertEquals("my-toolset", passed.getName());
        assertEquals(List.of("credentials", "storage", "folder1"), passed.getParentFolders());
        assertEquals("bucket-name", passed.getBucketName());
        assertEquals("bucket-location/", passed.getBucketLocation());

        Function<byte[], byte[]> fn = fnCaptor.getValue();
        byte[] updatedBody = fn.apply(OLD_ENCRYPTED_BODY);
        assertEquals(ENCRYPTED_BODY, updatedBody);
    }

    private CredentialsDescriptor createCredentialsDescriptor() {
        return CredentialsDescriptor.builder()
                .resourceId("bucket-name/folder1/my-toolset")
                .type(ResourceTypes.CREDENTIALS)
                .sourceType(SourceType.STORAGE)
                .name("my-toolset")
                .parentFolders(List.of("folder1"))
                .credentialsLevel(CredentialsLevel.USER)
                .bucketName("bucket-name")
                .bucketLocation("bucket-location/")
                .build();
    }

    private CredentialsLocator createCredentialsLocator() {
        return CredentialsLocator.builder()
                .resourceId("bucket-name/folder1/my-toolset")
                .type(ResourceTypes.CREDENTIALS)
                .sourceType(SourceType.STORAGE)
                .name("my-toolset")
                .parentFolders(List.of("folder1"))
                .buckets(List.of(CredentialBucketLocation.builder()
                        .credentialsLevel(CredentialsLevel.USER)
                        .bucketName("bucket-name")
                        .bucketLocation("bucket-location/")
                        .build()))
                .build();
    }

    private ResourceCredentials createCredentials(CredentialsLevel credentialsLevel) {
        return ResourceCredentials.builder()
                .credentialsLevel(credentialsLevel)
                .resourceId(TOOL_SET_NAME)
                .build();
    }

}
