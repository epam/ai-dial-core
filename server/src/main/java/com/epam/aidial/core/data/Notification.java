package com.epam.aidial.core.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class Notification implements Comparable<Notification> {
    String id;
    /**
     * Resource url notification belongs to
     */
    String url;
    /**
     * Notification type
     */
    NotificationType type;
    /**
     * Optional notification message. For example, reason for rejected publication request
     */
    String message;
    long timestamp;

    @Override
    public int compareTo(Notification notification) {
        return Long.compare(timestamp, notification.timestamp);
    }

    public static Notification getPublicationNotification(String url, String message) {
        return new Notification(generateNotificationId(), url, NotificationType.PUBLICATION, message, System.currentTimeMillis());
    }

    public static String generateNotificationId() {
        return UUID.randomUUID().toString();
    }

    public enum NotificationType {
        PUBLICATION
    }
}
