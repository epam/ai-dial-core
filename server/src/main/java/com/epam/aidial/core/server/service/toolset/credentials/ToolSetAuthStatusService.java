package com.epam.aidial.core.server.service.toolset.credentials;

import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.config.ToolsetAuthStatus;
import com.epam.aidial.core.server.data.toolset.credentials.ToolSetCredentials;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.Optional;

//TODO: move this logic to ToolsetAuthSettingsService?
@AllArgsConstructor
public class ToolSetAuthStatusService {

    private final ToolSetCredentialsManager toolSetCredentialsManager;

    public void setToolSetAuthStatuses(ToolSet toolSet) {
        List<ToolSetCredentials> allToolSetCredentials = toolSetCredentialsManager.getAllToolSetCredentials(toolSet.getName());
        setUserAuthStatus(toolSet, allToolSetCredentials);
        setGlobalAuthStatus(toolSet, allToolSetCredentials);
    }

    private void setUserAuthStatus(ToolSet toolSet,
                                   List<ToolSetCredentials> allToolSetCredentials) {
        Optional<ToolSetCredentials> userToolSetCredentials = allToolSetCredentials.stream()
            .filter(toolSetCredentials -> toolSetCredentials.getCredentialsLevel().equals(CredentialsLevel.USER))
            .findFirst();
        if (userToolSetCredentials.isPresent() && !userToolSetCredentials.get().isTokenExpired()) {
            toolSet.getAuthSettings().setUserLevelAuthStatus(ToolsetAuthStatus.SIGNED_IN);
        } else {
            toolSet.getAuthSettings().setUserLevelAuthStatus(ToolsetAuthStatus.SIGNED_OUT);
        }
    }

    private void setGlobalAuthStatus(ToolSet toolSet,
                                     List<ToolSetCredentials> allToolSetCredentials) {
        Optional<ToolSetCredentials> globalToolSetCredentials = allToolSetCredentials.stream()
            .filter(toolSetCredentials -> toolSetCredentials.getCredentialsLevel().equals(CredentialsLevel.GLOBAL))
            .findFirst();
        if (globalToolSetCredentials.isPresent() && !globalToolSetCredentials.get().isTokenExpired()) {
            toolSet.getAuthSettings().setGlobalAuthStatus(ToolsetAuthStatus.SIGNED_IN);
        } else {
            toolSet.getAuthSettings().setGlobalAuthStatus(ToolsetAuthStatus.SIGNED_OUT);
        }
    }
}
