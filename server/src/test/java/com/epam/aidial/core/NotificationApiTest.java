package com.epam.aidial.core;

import com.epam.aidial.core.data.Notification;
import org.junit.jupiter.api.Test;

import java.time.Instant;

public class NotificationApiTest extends ResourceBaseTest {

    @Test
    void testNotificationWorkflow() {
        Response response = operationRequest("/v1/ops/notification/list", "");
        verifyJson(response, 200, """
                {
                 "notifications": []
                }
                """);

        createNotification("id1", Instant.parse("2023-03-14T12:59:00Z").toEpochMilli(), "message1");
        createNotification("id2", Instant.parse("2023-03-14T11:59:00Z").toEpochMilli(), "message2");
        createNotification("id3", Instant.parse("2023-04-14T12:00:00Z").toEpochMilli(), "message3");

        response = operationRequest("/v1/ops/notification/list", "");
        verifyJson(response, 200, """
                {
                   "notifications":[
                      {
                      "id":"id2",
                      "url":"url",
                      "type":"PUBLICATION",
                      "message":"message2",
                      "timestamp":1678795140000
                      },
                      {
                      "id":"id1",
                      "url":"url",
                      "type":"PUBLICATION",
                      "message":"message1",
                      "timestamp":1678798740000
                      },
                      {
                      "id":"id3",
                      "url":"url",
                      "type":"PUBLICATION",
                      "message":"message3",
                      "timestamp":1681473600000
                      }
                   ]
                }
                """);

        response = operationRequest("/v1/ops/notification/delete", """
                {
                   "ids": ["id2", "id3"]
                }
                """);
        verify(response, 200);

        response = operationRequest("/v1/ops/notification/list", "");
        verifyJson(response, 200, """
                {
                   "notifications":[
                      {
                      "id":"id1",
                      "url":"url",
                      "type":"PUBLICATION",
                      "message":"message1",
                      "timestamp":1678798740000
                      }
                   ]
                }
                """);
    }

    private void createNotification(String id, long time, String content) {
        Notification notification = new Notification(id, "url", Notification.NotificationType.PUBLICATION, content, time);
        notificationService.createNotification(bucket, encryptionService.decrypt(bucket), notification);
    }
}
