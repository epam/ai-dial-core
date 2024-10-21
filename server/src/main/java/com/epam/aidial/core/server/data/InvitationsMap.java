package com.epam.aidial.core.server.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InvitationsMap {
    Map<String, Invitation> invitations;
}
