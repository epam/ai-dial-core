package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.util.ProxyUtil;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedByMeDtoTest {

    private static final String URL = "files/bucket/folder/file.txt";
    private static final String USER = "Users/alice/";

    @Test
    void testFileWithoutShareListMapReadsAsEmpty() {
        SharedByMeDto dto = ProxyUtil.convertToObject("""
                {"resourceToUsers": {"%s": ["%s"]}, "userIdToDisplayName": {"%s": "Alice"}}
                """.formatted(URL, USER, USER), SharedByMeDto.class);

        assertTrue(dto.getShareListResourcesToUsers().isEmpty());
        assertEquals(Map.of(USER, Set.of(ResourceAccessType.READ)), dto.getUserPermissions(URL));
    }

    @Test
    void testShareListIsAddedAggregatedAndRemoved() {
        SharedByMeDto dto = emptyDto();
        dto.addUserToResource(sharedResource(ResourceAccessType.READ, ResourceAccessType.SHARE_LIST), USER, "Alice");

        assertEquals(Map.of(URL, EnumSet.of(ResourceAccessType.READ, ResourceAccessType.SHARE_LIST)), dto.getAggregatedPermissions());
        assertEquals(Map.of(USER, EnumSet.of(ResourceAccessType.READ, ResourceAccessType.SHARE_LIST)), dto.getUserPermissions(URL));
        assertEquals(Set.of(USER), dto.collectUsersForPermissions(URL, Set.of(ResourceAccessType.SHARE_LIST)));

        dto.removePermissionsFromResource(URL, Set.of(ResourceAccessType.SHARE_LIST));

        assertEquals(Map.of(URL, EnumSet.of(ResourceAccessType.READ)), dto.getAggregatedPermissions());
    }

    @Test
    void testShareListSurvivesSerialization() {
        SharedByMeDto dto = emptyDto();
        dto.addUserToResource(sharedResource(ResourceAccessType.READ, ResourceAccessType.SHARE_LIST), USER, "Alice");

        SharedByMeDto read = ProxyUtil.convertToObject(ProxyUtil.convertToString(dto), SharedByMeDto.class);

        assertEquals(Map.of(URL, Set.of(USER)), read.getShareListResourcesToUsers());
    }

    @Test
    void testRecipientLeftWithoutReadOrWriteIsRemoved() {
        SharedByMeDto dto = emptyDto();
        dto.addUserToResource(sharedResource(ResourceAccessType.READ, ResourceAccessType.SHARE, ResourceAccessType.SHARE_LIST), USER, "Alice");

        dto.removePermissionsFromResource(URL, Set.of(ResourceAccessType.READ));

        assertTrue(dto.getUserPermissions(URL).isEmpty());
        assertTrue(dto.getAggregatedPermissions().isEmpty());
        assertTrue(dto.getUserIdToDisplayName().isEmpty());
    }

    @Test
    void testRevokeKeepsShareOfRecipientsWithWrite() {
        String writer = "Users/bob/";
        SharedByMeDto dto = emptyDto();
        dto.addUserToResource(sharedResource(ResourceAccessType.READ, ResourceAccessType.SHARE), USER, "Alice");
        dto.addUserToResource(sharedResource(ResourceAccessType.READ, ResourceAccessType.WRITE, ResourceAccessType.SHARE), writer, "Bob");

        dto.removePermissionsFromResource(URL, Set.of(ResourceAccessType.READ));

        assertEquals(Map.of(writer, EnumSet.of(ResourceAccessType.WRITE, ResourceAccessType.SHARE)), dto.getUserPermissions(URL));
        assertEquals(Map.of(writer, "Bob"), dto.getUserIdToDisplayName());
    }

    @Test
    void testRemovingUserDropsShareList() {
        SharedByMeDto dto = emptyDto();
        dto.addUserToResource(sharedResource(ResourceAccessType.READ, ResourceAccessType.SHARE_LIST), USER, "Alice");

        dto.removeUserFromResource(URL, USER);

        assertTrue(dto.getShareListResourcesToUsers().isEmpty());
        assertTrue(dto.getUserIdToDisplayName().isEmpty());
    }

    private static SharedByMeDto emptyDto() {
        return new SharedByMeDto(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
    }

    private static SharedResource sharedResource(ResourceAccessType... permissions) {
        return new SharedResource(URL, null, null, Set.of(permissions));
    }
}
