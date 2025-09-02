package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.data.credentials.ResourceTypes;
import com.epam.aidial.core.credentials.service.encryption.ContentEncryptionKeyService;
import com.epam.aidial.core.credentials.service.encryption.CredentialsEncryptionService;
import com.epam.aidial.core.credentials.util.JsonMapperUtil;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import org.apache.commons.lang3.NotImplementedException;
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
    public void testAddResourceCredentials_nonGlobal_throws() {
        ResourceCredentials creds = createCredentials(CredentialsLevel.USER);
        ResourceDescriptor descriptor = createResourceDescriptor();

        assertThrows(NotImplementedException.class, () -> service.addResourceCredentials(descriptor, creds));
        verifyNoInteractions(resourceService);
    }

    @Test
    public void testAddResourceCredentials_global_putsResource() {
        ResourceCredentials creds = createCredentials(CredentialsLevel.GLOBAL);
        ResourceDescriptor descriptor = createResourceDescriptor();
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
        assertEquals(List.of("credentials", "folder1"), passed.getParentFolders());
        assertEquals("bucket-name", passed.getBucketName());
        assertEquals("bucket-location/", passed.getBucketLocation());

        assertEquals(EtagHeader.ANY, etagCaptor.getValue());

        byte[] actualBody = bodyCaptor.getValue();
        assertEquals(ENCRYPTED_BODY, actualBody);
    }

    @Test
    public void testGetAllResourceCredentials_returnsOne() {
        ResourceCredentials creds = createCredentials(CredentialsLevel.GLOBAL);
        byte[] body = JsonMapperUtil.convertToString(creds).getBytes(StandardCharsets.UTF_8);
        ResourceDescriptor descriptor = createResourceDescriptor();

        when(resourceService.getResourceBytes(any(ResourceDescriptor.class))).thenReturn(ENCRYPTED_BODY);
        when(contentEncryptionKeyService.getKey(any())).thenReturn(CEK);
        when(credentialsEncryptionService.decrypt(any(), any(), any())).thenReturn(body);

        List<ResourceCredentials> list = service.getAllResourceCredentials(descriptor);

        assertNotNull(list);
        assertEquals(1, list.size());
        ResourceCredentials c = list.getFirst();
        assertEquals(CredentialsLevel.GLOBAL, c.getCredentialsLevel());
        assertEquals(TOOL_SET_NAME, c.getResourceId());

        ArgumentCaptor<ResourceDescriptor> descriptorCaptor = ArgumentCaptor.forClass(ResourceDescriptor.class);
        verify(resourceService).getResourceBytes(descriptorCaptor.capture());

        ResourceDescriptor passed = descriptorCaptor.getValue();
        Assertions.assertEquals(ResourceTypes.CREDENTIALS, passed.getType());
        assertEquals("my-toolset", passed.getName());
        assertEquals(List.of("credentials", "folder1"), passed.getParentFolders());
        assertEquals("bucket-name", passed.getBucketName());
        assertEquals("bucket-location/", passed.getBucketLocation());
    }

    @Test
    public void testGetAllResourceCredentials_returnsEmptyWhenNull() {
        ResourceDescriptor descriptor = createResourceDescriptor();
        when(resourceService.getResourceBytes(any(ResourceDescriptor.class))).thenReturn(null);

        List<ResourceCredentials> list = service.getAllResourceCredentials(descriptor);

        assertNotNull(list);
        assertTrue(list.isEmpty());
    }

    @Test
    public void testUpdateResourceCredentials_multipleEntries_throws() {
        ResourceDescriptor descriptor = createResourceDescriptor();
        ResourceCredentials c1 = createCredentials(CredentialsLevel.GLOBAL);
        ResourceCredentials c2 = createCredentials(CredentialsLevel.GLOBAL);

        assertThrows(UnsupportedOperationException.class, () -> service.updateResourceCredentials(descriptor, List.of(c1, c2)));
        verifyNoInteractions(resourceService);
    }

    @Test
    public void testUpdateResourceCredentials_nonGlobal_throws() {
        ResourceDescriptor descriptor = createResourceDescriptor();
        ResourceCredentials c1 = createCredentials(CredentialsLevel.USER);

        assertThrows(UnsupportedOperationException.class, () -> service.updateResourceCredentials(descriptor, List.of(c1)));
        verifyNoInteractions(resourceService);
    }

    @Test
    public void testUpdateResourceCredentials_notFound_throws() {
        ResourceDescriptor descriptor = createResourceDescriptor();
        ResourceCredentials c1 = createCredentials(CredentialsLevel.GLOBAL);

        doAnswer(inv -> {
            Function<String, String> mapper = inv.getArgument(1);
            mapper.apply(null); // should cause ResourceNotFoundException in service logic
            return null;
        }).when(resourceService).computeResourceBytes(any(ResourceDescriptor.class), any());

        assertThrows(ResourceNotFoundException.class, () -> service.updateResourceCredentials(descriptor, List.of(c1)));

        ArgumentCaptor<ResourceDescriptor> descriptorCaptor = ArgumentCaptor.forClass(ResourceDescriptor.class);
        verify(resourceService).computeResourceBytes(descriptorCaptor.capture(), any());
        ResourceDescriptor passed = descriptorCaptor.getValue();
        Assertions.assertEquals(ResourceTypes.CREDENTIALS, passed.getType());
        assertEquals("my-toolset", passed.getName());
        assertEquals(List.of("credentials", "folder1"), passed.getParentFolders());
        assertEquals("bucket-name", passed.getBucketName());
        assertEquals("bucket-location/", passed.getBucketLocation());
    }

    @Test
    public void testUpdateResourceCredentials_deleteWhenEmptyList() {
        ResourceDescriptor descriptor = createResourceDescriptor();

        service.updateResourceCredentials(descriptor, List.of());

        ArgumentCaptor<ResourceDescriptor> descriptorCaptor = ArgumentCaptor.forClass(ResourceDescriptor.class);
        ArgumentCaptor<Function<byte[], byte[]>> fnCaptor = ArgumentCaptor.forClass(Function.class);
        verify(resourceService).computeResourceBytes(descriptorCaptor.capture(), fnCaptor.capture());

        ResourceDescriptor passed = descriptorCaptor.getValue();
        Assertions.assertEquals(ResourceTypes.CREDENTIALS, passed.getType());
        assertEquals("my-toolset", passed.getName());
        assertEquals(List.of("credentials", "folder1"), passed.getParentFolders());
        assertEquals("bucket-name", passed.getBucketName());
        assertEquals("bucket-location/", passed.getBucketLocation());

        byte[] result = fnCaptor.getValue().apply(OLD_ENCRYPTED_BODY);
        assertNull(result);
    }

    @Test
    public void testUpdateResourceCredentials_updatesBody() {
        ResourceDescriptor descriptor = createResourceDescriptor();
        ResourceCredentials c1 = createCredentials(CredentialsLevel.GLOBAL);
        when(contentEncryptionKeyService.getOrCreateKey(any())).thenReturn(CEK);
        when(credentialsEncryptionService.encrypt(any(), any(), any())).thenReturn(ENCRYPTED_BODY);

        service.updateResourceCredentials(descriptor, List.of(c1));

        ArgumentCaptor<ResourceDescriptor> descriptorCaptor = ArgumentCaptor.forClass(ResourceDescriptor.class);
        ArgumentCaptor<Function<byte[], byte[]>> fnCaptor = ArgumentCaptor.forClass(Function.class);
        verify(resourceService).computeResourceBytes(descriptorCaptor.capture(), fnCaptor.capture());

        ResourceDescriptor passed = descriptorCaptor.getValue();
        Assertions.assertEquals(ResourceTypes.CREDENTIALS, passed.getType());
        assertEquals("my-toolset", passed.getName());
        assertEquals(List.of("credentials", "folder1"), passed.getParentFolders());
        assertEquals("bucket-name", passed.getBucketName());
        assertEquals("bucket-location/", passed.getBucketLocation());

        Function<byte[], byte[]> fn = fnCaptor.getValue();
        byte[] updatedBody = fn.apply(OLD_ENCRYPTED_BODY);
        assertEquals(ENCRYPTED_BODY, updatedBody);
    }

    private ResourceDescriptor createResourceDescriptor() {
        return new ResourceDescriptor(
                ResourceTypes.CREDENTIALS,
                "my-toolset",
                List.of("folder1"),
                "bucket-name",
                "bucket-location/",
                false
        );
    }

    private ResourceCredentials createCredentials(CredentialsLevel credentialsLevel) {
        return ResourceCredentials.builder()
                .credentialsLevel(credentialsLevel)
                .resourceId(TOOL_SET_NAME)
                .build();
    }

}
