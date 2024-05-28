package com.epam.aidial.core.service;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.DeleteNotificationRequest;
import com.epam.aidial.core.data.Notification;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.security.EncryptionService;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.ProxyUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@AllArgsConstructor
@Slf4j
public class NotificationService {

    private static final String NOTIFICATION_RESOURCE_FILENAME = "notifications";

    private static final TypeReference<Map<String, Notification>> NOTIFICATIONS_TYPE = new TypeReference<>() {
    };

    private final ResourceService resourceService;
    private final EncryptionService encryptionService;


    public Notification createNotification(String bucketName, String bucketLocation, Notification notification) {
        ResourceDescription notificationResource = getNotificationResource(bucketName, bucketLocation);

        resourceService.computeResource(notificationResource, body -> {
            Map<String, Notification> notifications = decodeNotifications(body);
            notifications.put(notification.getId(), notification);

            return ProxyUtil.convertToString(notifications);
        });

        return notification;
    }

    public Set<Notification> listNotification(ProxyContext context) {
        ResourceDescription notificationResource = getNotificationResource(context, encryptionService);
        Map<String, Notification> notifications = decodeNotifications(resourceService.getResource(notificationResource));

        return new TreeSet<>(notifications.values());
    }

    public void deleteNotification(ProxyContext context, DeleteNotificationRequest request) {
        List<String> ids = request.ids();
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("Notification IDs cannot be empty");
        }
        ResourceDescription notificationResource = getNotificationResource(context, encryptionService);
        resourceService.computeResource(notificationResource, state -> {
            Map<String, Notification> notifications = decodeNotifications(state);
            ids.forEach(notifications::remove);

            return ProxyUtil.convertToString(notifications);
        });
    }

    private static ResourceDescription getNotificationResource(ProxyContext context, EncryptionService encryptionService) {
        String bucketLocation = BlobStorageUtil.buildInitiatorBucket(context);
        String bucketName = encryptionService.encrypt(bucketLocation);
        return getNotificationResource(bucketName, bucketLocation);
    }

    private static ResourceDescription getNotificationResource(String bucketName, String bucketLocation) {
        return ResourceDescription.fromDecoded(ResourceType.NOTIFICATION, bucketName, bucketLocation, NOTIFICATION_RESOURCE_FILENAME);
    }

    private static Map<String, Notification> decodeNotifications(String json) {
        Map<String, Notification> notifications = ProxyUtil.convertToObject(json, NOTIFICATIONS_TYPE);
        return (notifications == null) ? new LinkedHashMap<>() : notifications;
    }
}
