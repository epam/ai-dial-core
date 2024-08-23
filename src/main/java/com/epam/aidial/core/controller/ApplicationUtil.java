package com.epam.aidial.core.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.data.ApplicationData;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@UtilityClass
@Slf4j
public class ApplicationUtil {

    public ApplicationData mapApplication(Application application) {
        ApplicationData data = new ApplicationData();
        data.setId(application.getName());
        data.setApplication(application.getName());
        data.setDisplayName(application.getDisplayName());
        data.setDisplayVersion(application.getDisplayVersion());
        data.setIconUrl(application.getIconUrl());
        data.setDescription(application.getDescription());
        data.setFeatures(DeploymentController.createFeatures(application.getFeatures()));
        data.setInputAttachmentTypes(application.getInputAttachmentTypes());
        data.setMaxInputAttachments(application.getMaxInputAttachments());
        data.setDefaults(application.getDefaults());

        String reference = application.getReference();
        data.setReference(reference == null ? application.getName() : reference);

        return data;
    }

    public String generateReference() {
        return UUID.randomUUID().toString();
    }
}
