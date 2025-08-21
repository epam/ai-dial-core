package com.epam.aidial.core.server.service.credentials;

import com.epam.aidial.core.server.data.toolset.credentials.ToolSetCredentials;
import com.epam.aidial.core.server.service.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class ToolSetCredentialsService {


    // TODO: replace with persistent storage
    private final Map<String, List<ToolSetCredentials>> toolSetCredentialsMap = new HashMap<>();


    public void addToolSetCredentials(ToolSetCredentials toolSetCredentials) {
        toolSetCredentialsMap.computeIfAbsent(toolSetCredentials.getToolSetName(), k -> new ArrayList<>()).add(toolSetCredentials);
    }

    public List<ToolSetCredentials> getAllToolSetCredentials(String toolSetName) {
        return toolSetCredentialsMap.getOrDefault(toolSetName, new ArrayList<>());
    }

    public void updateToolSetCredentials(String toolSetName,
                                         List<ToolSetCredentials> updatedToolSetCredentialsList) {
        if (!toolSetCredentialsMap.containsKey(toolSetName)) {
            throw new ResourceNotFoundException(String.format("Credentials for ToolSet %s not found", toolSetName));
        }

        if (updatedToolSetCredentialsList.isEmpty()) {
            toolSetCredentialsMap.remove(toolSetName);
        }

        toolSetCredentialsMap.put(toolSetName, updatedToolSetCredentialsList);
    }

}
