package com.epam.aidial.core.server.service.schemarichapps;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.Publication;
import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.util.ApplicationTypeSchemaUtils;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SchemaRichApplicationPublicationService {

    private final ApplicationService applicationService;
    private final EncryptionService encryptionService;
    private final ResourceService resourceService;
    private final FolderService folderService;

    public SchemaRichApplicationPublicationService(ApplicationService applicationService, EncryptionService encryptionService, ResourceService resourceService,
                                                   FolderService folderService) {
        this.applicationService = applicationService;
        this.encryptionService = encryptionService;
        this.resourceService = resourceService;
        this.folderService = folderService;
    }

    public void addCustomApplicationRelatedFiles(ProxyContext context, Publication publication) {
        if (publication.getResources().isEmpty()) {
            return;
        }

        List<String> otherSourceUrlsFromRequest = publication.getResources().stream()
                .map(Publication.Resource::getSourceUrl)
                .toList();

        Map<String, Integer> fileNamesTaken = new HashMap<>();

        List<Publication.Resource> newResources = publication.getResources().stream()
                .filter(resource -> resource.getAction() != Publication.ResourceAction.DELETE)
                .flatMap(resource -> replaceApplicationWithItsFilesAndFolders(context, resource, otherSourceUrlsFromRequest, fileNamesTaken))
                .toList();

        publication.getResources().addAll(newResources);
    }

    private String buildTargetFolderForCustomAppFiles(String targetUrl) {
        ResourceDescriptor targetResourceDescriptor = ResourceDescriptorFactory.fromAnyUrl(targetUrl, encryptionService);
        if (targetResourceDescriptor.isFolder()) {
            throw new IllegalArgumentException("Target url must be a file");
        }
        if (targetResourceDescriptor.getType() != ResourceTypes.APPLICATION) {
            throw new IllegalArgumentException("Target url must be an application type");
        }
        String appName = targetResourceDescriptor.getName();
        String appPath = targetResourceDescriptor.getParentPath();
        if (appPath == null) {
            return "." + appName + ResourceDescriptor.PATH_SEPARATOR;
        } else {
            return appPath + ResourceDescriptor.PATH_SEPARATOR + "." + appName + ResourceDescriptor.PATH_SEPARATOR;
        }
    }

    private Stream<Publication.Resource> replaceApplicationWithItsFilesAndFolders(ProxyContext context, Publication.Resource pubicationResource,
                                                                                  List<String> otherSourceUrlsFromRequest, Map<String, Integer> fileNamesTaken) {
        ResourceDescriptor resourceToPublish = ResourceDescriptorFactory.fromAnyUrl(pubicationResource.getSourceUrl(), encryptionService);
        if (resourceToPublish.getType() != ResourceTypes.APPLICATION) {
            return Stream.empty();
        }

        Application applicationToPublish = applicationService.getApplication(resourceToPublish).getValue();
        if (applicationToPublish.getApplicationTypeSchemaId() == null) {
            return Stream.empty();
        }

        String targetFolder = buildTargetFolderForCustomAppFiles(pubicationResource.getTargetUrl());

        List<ResourceDescriptor> applicationsOwnDescriptors = ApplicationTypeSchemaUtils.getFiles(context.getConfig(), applicationToPublish, encryptionService, resourceService)
                .stream()
                .filter(descriptor -> !otherSourceUrlsFromRequest.contains(descriptor.getUrl()))
                .toList();

        Stream<Publication.Resource> folderDescriptors = applicationsOwnDescriptors.stream()
                .filter(ResourceDescriptor::isFolder)
                .flatMap(folder -> createFolderResourcesWithUniqueName(folder, targetFolder, fileNamesTaken, pubicationResource.getAction()));

        Stream<Publication.Resource> fileDescriptors = applicationsOwnDescriptors.stream()
                .filter(descriptor -> !descriptor.isFolder())
                .map(file -> createSingleFileResourceWithUniqueName(file, targetFolder, fileNamesTaken, pubicationResource.getAction()));

        return Stream.concat(folderDescriptors, fileDescriptors);
    }

    private Stream<Publication.Resource> createFolderResourcesWithUniqueName(ResourceDescriptor sourceFolderDescriptor, String targetFolderUrl,
                                                                             Map<String, Integer> fileNamesTaken, Publication.ResourceAction action) {
        String targetSubFolderUrl = uniqueTargetResourceUrl(sourceFolderDescriptor, targetFolderUrl, fileNamesTaken);
        return folderService.getFilesFromFolderWithSubFolders(sourceFolderDescriptor)
                .map(sourceFileDescriptor ->
                        createResourceForFileAndFolderDescriptor(
                                sourceFileDescriptor, sourceFolderDescriptor, targetSubFolderUrl, action)
                );
    }

    private static Publication.Resource createSingleFileResourceWithUniqueName(ResourceDescriptor sourceFileDescriptor,
                                                                        String targetFolderUrl,
                                                                        Map<String, Integer> fileNamesTaken,
                                                                        Publication.ResourceAction action) {
        String targetUrl = uniqueTargetResourceUrl(sourceFileDescriptor, targetFolderUrl, fileNamesTaken);

        return new Publication.Resource()
                .setAction(action)
                .setSourceUrl(sourceFileDescriptor.getUrl())
                .setTargetUrl(targetUrl);
    }

    private static String uniqueTargetResourceUrl(ResourceDescriptor sourceDescriptor, String targetFolderUrl, Map<String, Integer> fileNamesTaken) {
        String fileName = sourceDescriptor.getName();
        int count = fileNamesTaken.getOrDefault(fileName, 0) + 1;
        fileNamesTaken.put(fileName, count);

        if (count > 1) {
            // Add counter to filename while preserving extension
            fileName = fileName.replaceFirst("(\\.[^.]+)$", "_" + count + "$1");
        }

        return ResourceDescriptorFactory.fromDecoded(
                sourceDescriptor.getType(),
                ResourceDescriptor.PUBLIC_BUCKET,
                ResourceDescriptor.PATH_SEPARATOR,
                targetFolderUrl + fileName).getUrl();
    }

    private static Publication.Resource createResourceForFileAndFolderDescriptor(ResourceDescriptor sourceFileDescriptor,
                                                                          ResourceDescriptor sourceFolderDescriptor,
                                                                          String targetFolderUrl,
                                                                          Publication.ResourceAction action) {
        String relativeFilePath = sourceFolderDescriptor.getRelativePath(sourceFileDescriptor);
        String targetUrl = targetFolderUrl + ResourceDescriptor.PATH_SEPARATOR + relativeFilePath;
        return new Publication.Resource()
                .setAction(action)
                .setSourceUrl(sourceFileDescriptor.getUrl())
                .setTargetUrl(targetUrl);
    }

}
