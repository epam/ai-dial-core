package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.Notification;
import com.epam.aidial.core.server.data.Publication;
import com.epam.aidial.core.server.data.RejectPublicationRequest;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.resource.ComplexResourceService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How a decision on a publication request treats unit approvers, with storage and the org unit checks mocked.
 */
class PublicationServiceTest {

    private static final String URL = "publications/bucket/7";

    private final Map<String, String> documents = new HashMap<>();
    private final ResourceService resources = mock(ResourceService.class);
    private final AccessService access = mock(AccessService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final OrgUnitApprovals approvals = mock(OrgUnitApprovals.class);
    private final ProxyContext context = mock(ProxyContext.class);
    private final AtomicBoolean locked = new AtomicBoolean();
    private final AtomicBoolean decidedWhileLocked = new AtomicBoolean();
    private final ResourceDescriptor resource = ResourceDescriptorFactory.fromDecoded(ResourceTypes.PUBLICATION, "bucket", "Users/alice/", "7");
    private PublicationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        Publication publication = new Publication().setUrl(URL).setTargetFolder("public/Acme/").setResources(new ArrayList<>())
                .setStatus(Publication.Status.PENDING);
        documents.put("Users/alice/", ProxyUtil.convertToString(Map.of(URL, publication)));
        when(resources.getResource(any())).thenAnswer(invocation -> documents.get(location(invocation.getArgument(0))));
        when(resources.computeResource(any(), any(Function.class))).thenAnswer(invocation -> {
            String key = location(invocation.getArgument(0));
            locked.set(true);
            try {
                String after = ((Function<String, String>) invocation.getArgument(1)).apply(documents.get(key));
                documents.put(key, after);
            } finally {
                locked.set(false);
            }
            return null;
        });
        when(approvals.approvesAnyUnit(any())).thenReturn(true);
        when(approvals.mayDecide(any(), any())).thenAnswer(invocation -> {
            decidedWhileLocked.compareAndSet(false, locked.get());
            return true;
        });
        EncryptionService encryption = mock(EncryptionService.class);
        when(encryption.encrypt(anyString())).thenReturn("bucket");
        service = new PublicationService(encryption, resources, access, mock(RuleService.class), notifications,
                mock(ApplicationService.class), mock(ToolSetService.class), mock(ResourceOperationService.class),
                mock(ComplexResourceService.class), mock(OrgUnitService.class), approvals, () -> "id", () -> 0L);
    }

    @Test
    void testRejectDecidesBeforeLockingTheRequests() {
        service.rejectPublication(context, resource, new RejectPublicationRequest(URL, "no"));

        verify(approvals).mayDecide(any(), any());
        assertFalse(decidedWhileLocked.get());
    }

    @Test
    void testApproveLeavesTheCallerCheckToItsCallerButStillRefusesOthers() {
        service.approvePublication(context, resource);
        verify(approvals, never()).approvesAnyUnit(any());

        reset();
        when(approvals.mayDecide(any(), any())).thenReturn(false);
        HttpException refused = assertThrows(HttpException.class, () -> service.approvePublication(context, resource));
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatus());
    }

    @Test
    void testApproverDecisionsDontSayAdmin() {
        service.approvePublication(context, resource);
        assertEquals("Your request has been approved", lastNotification());

        reset();
        service.rejectPublication(context, resource, new RejectPublicationRequest(URL, "too early"));
        assertEquals("Your request has been rejected: too early", lastNotification());
    }

    @Test
    void testAdminDecisionsKeepTheirWording() {
        when(access.hasAdminAccess(context)).thenReturn(true);

        service.approvePublication(context, resource);
        assertEquals("Your request has been approved by admin", lastNotification());

        reset();
        service.rejectPublication(context, resource, new RejectPublicationRequest(URL, null));
        assertEquals("Your request has been rejected by admin", lastNotification());
    }

    private void reset() {
        documents.computeIfPresent("Users/alice/", (key, value) -> value.replace("\"APPROVED\"", "\"PENDING\""));
        assertTrue(documents.get("Users/alice/").contains("PENDING"));
    }

    private String lastNotification() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notifications, atLeastOnce()).createNotification(any(), any(), captor.capture());
        List<Notification> all = captor.getAllValues();
        return all.get(all.size() - 1).getMessage();
    }

    private static String location(ResourceDescriptor descriptor) {
        return descriptor.getBucketLocation();
    }
}
