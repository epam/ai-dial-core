package com.epam.aidial.core.server.service.credentials;

import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.data.toolset.credentials.ToolSetCredentials;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.ResourceNotFoundException;
import com.epam.aidial.core.server.service.credentials.encryption.ContentEncryptionKeyService;
import com.epam.aidial.core.server.service.credentials.encryption.ToolsetCredentialsEncryptionService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import org.apache.commons.lang3.NotImplementedException;
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
class ToolSetCredentialsServiceTest {

    private static final String TOOL_SET_NAME = "toolsets/bucket-name/folder1/my-toolset";
    private static final byte[] CEK = "test_cek".getBytes();
    private static final byte[] OLD_ENCRYPTED_BODY = "old_encrypted_body".getBytes();
    private static final byte[] ENCRYPTED_BODY = "encrypted_body".getBytes();

    @Mock
    private ResourceService resourceService;
    @Mock
    private EncryptionService encryptionService;
    @Mock
    private ContentEncryptionKeyService contentEncryptionKeyService;
    @Mock
    private ToolsetCredentialsEncryptionService toolsetCredentialsEncryptionService;

    @InjectMocks
    private ToolSetCredentialsService service;

    private ToolSetCredentials makeCredentials(CredentialsLevel credentialsLevel) {
        return ToolSetCredentials.builder()
                .credentialsLevel(credentialsLevel)
                .toolSetName(TOOL_SET_NAME)
                .build();
    }

    @Test
    public void testAddToolSetCredentials_nonGlobal_throws() {
        ToolSetCredentials creds = makeCredentials(CredentialsLevel.USER);

        assertThrows(NotImplementedException.class, () -> service.addToolSetCredentials(creds));
        verifyNoInteractions(resourceService);
    }

    @Test
    public void testAddToolSetCredentials_global_putsResource() {
        ToolSetCredentials creds = makeCredentials(CredentialsLevel.GLOBAL);
        when(encryptionService.decrypt(any())).thenReturn("bucket-location/");
        when(contentEncryptionKeyService.getOrCreateKey(any())).thenReturn(CEK);
        when(toolsetCredentialsEncryptionService.encrypt(any(), any(), any())).thenReturn(ENCRYPTED_BODY);

        service.addToolSetCredentials(creds);

        ArgumentCaptor<ResourceDescriptor> descriptorCaptor = ArgumentCaptor.forClass(ResourceDescriptor.class);
        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<EtagHeader> etagCaptor = ArgumentCaptor.forClass(EtagHeader.class);

        verify(resourceService).putResourceBytes(descriptorCaptor.capture(), bodyCaptor.capture(), etagCaptor.capture());

        ResourceDescriptor passed = descriptorCaptor.getValue();
        assertEquals(ResourceTypes.TOOL_SET_CREDENTIALS, passed.getType());
        assertEquals("my-toolset", passed.getName());
        assertEquals(List.of("credentials", "folder1"), passed.getParentFolders());
        assertEquals("bucket-name", passed.getBucketName());
        assertEquals("bucket-location/", passed.getBucketLocation());

        assertEquals(EtagHeader.ANY, etagCaptor.getValue());

        byte[] actualBody = bodyCaptor.getValue();
        assertEquals(ENCRYPTED_BODY, actualBody);
    }

    @Test
    public void testGetAllToolSetCredentials_returnsOne() {
        ToolSetCredentials creds = makeCredentials(CredentialsLevel.GLOBAL);
        byte[] body = ProxyUtil.convertToString(creds).getBytes(StandardCharsets.UTF_8);

        when(resourceService.getResourceBytes(any(ResourceDescriptor.class))).thenReturn(ENCRYPTED_BODY);
        when(encryptionService.decrypt(any())).thenReturn("bucket-location/");
        when(contentEncryptionKeyService.getKey(any())).thenReturn(CEK);
        when(toolsetCredentialsEncryptionService.decrypt(any(), any(), any())).thenReturn(body);

        List<ToolSetCredentials> list = service.getAllToolSetCredentials(TOOL_SET_NAME);

        assertNotNull(list);
        assertEquals(1, list.size());
        ToolSetCredentials c = list.getFirst();
        assertEquals(CredentialsLevel.GLOBAL, c.getCredentialsLevel());
        assertEquals(TOOL_SET_NAME, c.getToolSetName());

        ArgumentCaptor<ResourceDescriptor> descriptorCaptor = ArgumentCaptor.forClass(ResourceDescriptor.class);
        verify(resourceService).getResourceBytes(descriptorCaptor.capture());

        ResourceDescriptor passed = descriptorCaptor.getValue();
        assertEquals(ResourceTypes.TOOL_SET_CREDENTIALS, passed.getType());
        assertEquals("my-toolset", passed.getName());
        assertEquals(List.of("credentials", "folder1"), passed.getParentFolders());
        assertEquals("bucket-name", passed.getBucketName());
        assertEquals("bucket-location/", passed.getBucketLocation());
    }

    @Test
    public void testGetAllToolSetCredentials_returnsEmptyWhenNull() {
        when(encryptionService.decrypt(any())).thenReturn("bucket-location/");
        when(resourceService.getResourceBytes(any(ResourceDescriptor.class))).thenReturn(null);

        List<ToolSetCredentials> list = service.getAllToolSetCredentials(TOOL_SET_NAME);

        assertNotNull(list);
        assertTrue(list.isEmpty());
    }

    @Test
    public void testUpdateToolSetCredentials_multipleEntries_throws() {
        ToolSetCredentials c1 = makeCredentials(CredentialsLevel.GLOBAL);
        ToolSetCredentials c2 = makeCredentials(CredentialsLevel.GLOBAL);

        assertThrows(UnsupportedOperationException.class, () -> service.updateToolSetCredentials(TOOL_SET_NAME, List.of(c1, c2)));
        verifyNoInteractions(resourceService);
    }

    @Test
    public void testUpdateToolSetCredentials_nonGlobal_throws() {
        ToolSetCredentials c1 = makeCredentials(CredentialsLevel.USER);

        assertThrows(UnsupportedOperationException.class, () -> service.updateToolSetCredentials(TOOL_SET_NAME, List.of(c1)));
        verifyNoInteractions(resourceService);
    }

    @Test
    public void testUpdateToolSetCredentials_notFound_throws() {
        ToolSetCredentials c1 = makeCredentials(CredentialsLevel.GLOBAL);

        when(encryptionService.decrypt(any())).thenReturn("bucket-location/");
        doAnswer(inv -> {
            Function<String, String> mapper = inv.getArgument(1);
            mapper.apply(null); // should cause ResourceNotFoundException in service logic
            return null;
        }).when(resourceService).computeResourceBytes(any(ResourceDescriptor.class), any());

        assertThrows(ResourceNotFoundException.class, () -> service.updateToolSetCredentials(TOOL_SET_NAME, List.of(c1)));

        ArgumentCaptor<ResourceDescriptor> descriptorCaptor = ArgumentCaptor.forClass(ResourceDescriptor.class);
        verify(resourceService).computeResourceBytes(descriptorCaptor.capture(), any());
        ResourceDescriptor passed = descriptorCaptor.getValue();
        assertEquals(ResourceTypes.TOOL_SET_CREDENTIALS, passed.getType());
        assertEquals("my-toolset", passed.getName());
        assertEquals(List.of("credentials", "folder1"), passed.getParentFolders());
        assertEquals("bucket-name", passed.getBucketName());
        assertEquals("bucket-location/", passed.getBucketLocation());
    }

    @Test
    public void testUpdateToolSetCredentials_deleteWhenEmptyList() {
        when(encryptionService.decrypt(any())).thenReturn("bucket-location/");

        service.updateToolSetCredentials(TOOL_SET_NAME, List.of());

        ArgumentCaptor<ResourceDescriptor> descriptorCaptor = ArgumentCaptor.forClass(ResourceDescriptor.class);
        ArgumentCaptor<Function<byte[], byte[]>> fnCaptor = ArgumentCaptor.forClass(Function.class);
        verify(resourceService).computeResourceBytes(descriptorCaptor.capture(), fnCaptor.capture());

        ResourceDescriptor passed = descriptorCaptor.getValue();
        assertEquals(ResourceTypes.TOOL_SET_CREDENTIALS, passed.getType());
        assertEquals("my-toolset", passed.getName());
        assertEquals(List.of("credentials", "folder1"), passed.getParentFolders());
        assertEquals("bucket-name", passed.getBucketName());
        assertEquals("bucket-location/", passed.getBucketLocation());

        byte[] result = fnCaptor.getValue().apply(OLD_ENCRYPTED_BODY);
        assertNull(result);
    }

    @Test
    public void testUpdateToolSetCredentials_updatesBody() {
        ToolSetCredentials c1 = makeCredentials(CredentialsLevel.GLOBAL);
        when(encryptionService.decrypt(any())).thenReturn("bucket-location/");
        when(contentEncryptionKeyService.getOrCreateKey(any())).thenReturn(CEK);
        when(toolsetCredentialsEncryptionService.encrypt(any(), any(), any())).thenReturn(ENCRYPTED_BODY);

        service.updateToolSetCredentials(TOOL_SET_NAME, List.of(c1));

        ArgumentCaptor<ResourceDescriptor> descriptorCaptor = ArgumentCaptor.forClass(ResourceDescriptor.class);
        ArgumentCaptor<Function<byte[], byte[]>> fnCaptor = ArgumentCaptor.forClass(Function.class);
        verify(resourceService).computeResourceBytes(descriptorCaptor.capture(), fnCaptor.capture());

        ResourceDescriptor passed = descriptorCaptor.getValue();
        assertEquals(ResourceTypes.TOOL_SET_CREDENTIALS, passed.getType());
        assertEquals("my-toolset", passed.getName());
        assertEquals(List.of("credentials", "folder1"), passed.getParentFolders());
        assertEquals("bucket-name", passed.getBucketName());
        assertEquals("bucket-location/", passed.getBucketLocation());

        Function<byte[], byte[]> fn = fnCaptor.getValue();
        byte[] updatedBody = fn.apply(OLD_ENCRYPTED_BODY);
        assertEquals(ENCRYPTED_BODY, updatedBody);
    }

}
