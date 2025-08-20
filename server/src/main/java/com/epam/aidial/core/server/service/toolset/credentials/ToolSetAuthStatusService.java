package com.epam.aidial.core.server.service.toolset.credentials;

import com.epam.aidial.core.config.AuthenticationType;
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

    private final ToolSetCredentialsService credentialsService;

    public void setToolSetAuthStatuses(ToolSet toolSet) {
        List<ToolSetCredentials> allToolSetCredentials = credentialsService.getAllToolSetCredentials(toolSet.getName());
        setUserAuthStatus(toolSet, allToolSetCredentials);
        setGlobalAuthStatus(toolSet, allToolSetCredentials);
    }

    private void setUserAuthStatus(ToolSet toolSet,
                                   List<ToolSetCredentials> allToolSetCredentials) {
        Optional<ToolSetCredentials> userToolSetCredentials = allToolSetCredentials.stream()
            .filter(toolSetCredentials -> toolSetCredentials.getCredentialsLevel().equals(CredentialsLevel.USER))
            .findFirst();
        if (userToolSetCredentials.isPresent() && verifyToolSetCredentialsValid(userToolSetCredentials.get())) {
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
        if (globalToolSetCredentials.isPresent() && verifyToolSetCredentialsValid(globalToolSetCredentials.get())) {
            toolSet.getAuthSettings().setGlobalAuthStatus(ToolsetAuthStatus.SIGNED_IN);
        } else {
            toolSet.getAuthSettings().setGlobalAuthStatus(ToolsetAuthStatus.SIGNED_OUT);
        }
    }

    private boolean verifyToolSetCredentialsValid(ToolSetCredentials toolSetCredentials) {
        if (toolSetCredentials.getAuthenticationType().equals(AuthenticationType.OAUTH)) {
            return toolSetCredentials.getCreatedAt() + toolSetCredentials.getExpiresIn() * 1000 > System.currentTimeMillis();
        }
        return true;
    }
}
