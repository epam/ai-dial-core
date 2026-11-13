package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.config.InvalidEntityRecord;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.DiscoveredEdge.EdgeStatus;
import com.epam.aidial.core.server.service.ExportDiscovery.DiscoveryIssue;
import com.epam.aidial.core.server.service.SchemaReferenceResolver.SchemaResolution;
import com.epam.aidial.core.server.service.ShortNameResolution.Origin;
import com.epam.aidial.core.server.service.ShortNameResolver.ClassifiedReference;
import com.epam.aidial.core.server.service.ShortNameResolver.ReferenceForm;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.validation.CollectionIssue;
import com.epam.aidial.core.server.validation.CollectionIssueKind;
import com.epam.aidial.core.server.validation.MarkerCollection;
import com.epam.aidial.core.server.validation.MarkerCollection.MarkerReference;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Discovers the outgoing references of one Deployment and classifies each, without reading a body, authorizing, or checking that a
 * URL-form target exists. Short names go through the resolver, which reads the snapshot it was built from, so one export looks every
 * name up in one generation.
 *
 * <p>Covered: {@code interceptors}, {@code dependencies}, {@code icon_url}, {@code catalog_schema_id}, the markers of
 * {@code catalog_properties}, and for an Application its application type schema id, {@code function.source_folder} and the markers of
 * {@code application_properties}. A schema edge is {@code missing} when no schema has the id, {@code schema-unavailable} when it cannot
 * be evaluated, and otherwise follows {@link SchemaReferenceResolver}.
 */
public final class DeploymentEdgeDiscovery {

    private static final String CATALOG_PROPERTIES_POINTER = "/catalog_properties";
    private static final String APPLICATION_PROPERTIES_POINTER = "/application_properties";
    private static final MarkerCollection NO_MARKERS = new MarkerCollection(List.of(), List.of());

    private final Config snapshotConfig;
    private final ShortNameResolver resolver;
    private final SchemaReferenceResolver schemaResolver;
    private final ApplicationSchemaService schemaService;
    private final CatalogSchemaService catalogSchemaService;
    private final EncryptionService encryptionService;
    private final ExportLimits limits;
    private final ExportLimits.Deadline deadline;
    // one build per schema id: it can download the document of the runner, and many Applications share one schema
    private final Map<URI, ApplicationSchemaService.ExportSchema> builtSchemas = new HashMap<>();

    /**
     * @param merged     the merged {@code Config} that all lookups read, so that one export sees one generation
     * @param fileConfig the file-sourced {@code Config}, or {@code null} when the file store has none
     * @param limits     {@code maxEdges} bounds the references of one body: more fails the export
     */
    public DeploymentEdgeDiscovery(Config merged, Map<ResourceTypes, Map<String, InvalidEntityRecord>> invalidEntities,
                                    @Nullable Config fileConfig, ResourceService resourceService, ApplicationSchemaService schemaService,
                                    CatalogSchemaService catalogSchemaService, EncryptionService encryptionService,
                                    ExportLimits limits, ExportLimits.Deadline deadline) {
        this.limits = limits;
        this.deadline = deadline;
        this.snapshotConfig = merged;
        this.resolver = new ShortNameResolver(merged, invalidEntities, fileConfig, resourceService);
        this.schemaResolver = new SchemaReferenceResolver(merged, invalidEntities, fileConfig, resourceService, deadline);
        this.schemaService = schemaService;
        this.catalogSchemaService = catalogSchemaService;
        this.encryptionService = encryptionService;
    }

    /**
     * @param deployment only read, never changed
     */
    public ExportDiscovery discover(Deployment deployment) {
        Application application = deployment instanceof Application candidate ? candidate : null;
        MarkerCollection markers = application == null ? NO_MARKERS : collectApplicationMarkers(application);
        MarkerCollection catalogMarkers = catalogSchemaService.collectMarkerReferences(deployment, snapshotConfig);

        if (markers.references().size() + catalogMarkers.references().size() > limits.maxEdges()) {
            // before any is classified, so that a body of this kind costs no storage probe
            throw ExportLimits.newExceededException("references");
        }
        List<DiscoveredEdge> edges = new ArrayList<>();
        if (application != null && application.getApplicationTypeSchemaId() != null) {
            edges.add(classifySchema("/application_type_schema_id", ResourceTypes.APP_TYPE_SCHEMA,
                    application.getApplicationTypeSchemaId(), isSchemaUnavailable(markers)));
        }
        List<String> interceptors = deployment.getInterceptors();
        for (int i = 0; interceptors != null && i < interceptors.size(); i++) {
            checkRoom(edges);
            edges.add(classifyInterceptor("/interceptors/" + i, interceptors.get(i)));
        }
        List<String> dependencies = deployment.getDependencies();
        for (int i = 0; dependencies != null && i < dependencies.size(); i++) {
            checkRoom(edges);
            edges.add(classifyValue("/dependencies/" + i, dependencies.get(i), false));
        }
        if (deployment.getCatalogSchemaId() != null) {
            edges.add(classifySchema("/catalog_schema_id", ResourceTypes.CATALOG_SCHEMA,
                    deployment.getCatalogSchemaId(), isSchemaUnavailable(catalogMarkers)));
        }
        if (deployment.getIconUrl() != null && !deployment.getIconUrl().isBlank()) {
            edges.add(classifyIcon(deployment.getIconUrl()));
        }
        if (application != null && application.getFunction() != null && application.getFunction().getSourceFolder() != null) {
            edges.add(classifySourceFolder());
        }

        addMarkerEdges(edges, markers, APPLICATION_PROPERTIES_POINTER);
        addMarkerEdges(edges, catalogMarkers, CATALOG_PROPERTIES_POINTER);
        return toDiscovery(List.copyOf(edges), markers, catalogMarkers);
    }

    /** The marked values of {@code application_properties} and the issues of its schema. */
    private MarkerCollection collectApplicationMarkers(Application application) {
        URI schemaId = application.getApplicationTypeSchemaId();
        if (schemaId == null) {
            return NO_MARKERS;
        }
        ApplicationSchemaService.ExportSchema built =
                builtSchemas.computeIfAbsent(schemaId, key -> schemaService.buildExportSchema(key, snapshotConfig));
        if (built.issue() != null) {
            return MarkerCollection.ofIssue(built.issue());
        }
        Map<String, Object> properties = application.getApplicationProperties();
        if (properties == null || properties.isEmpty()) {
            return NO_MARKERS;
        }
        return schemaService.collectMarkerReferences(application, built.schema());
    }

    /**
     * The issues of both collections as issues of the Application body: a schema that cannot be used is at the pointer of the schema id,
     * any other issue is at its own pointer inside the body.
     */
    static ExportDiscovery toDiscovery(List<DiscoveredEdge> edges, MarkerCollection markers, MarkerCollection catalogMarkers) {
        List<DiscoveryIssue> issues = new ArrayList<>();
        for (CollectionIssue issue : markers.issues()) {
            boolean isSchemaIssue = issue.kind() == CollectionIssueKind.SCHEMA_NOT_FOUND || issue.kind() == CollectionIssueKind.SCHEMA_UNAVAILABLE;
            String pointer = isSchemaIssue ? "/application_type_schema_id" : APPLICATION_PROPERTIES_POINTER + issue.pointer();
            issues.add(new DiscoveryIssue(issue.kind(), pointer));
        }
        addIssues(issues, catalogMarkers, CATALOG_PROPERTIES_POINTER);
        return new ExportDiscovery(edges, List.copyOf(issues));
    }

    private static void addIssues(List<DiscoveryIssue> issues, MarkerCollection collection, String basePointer) {
        for (CollectionIssue issue : collection.issues()) {
            issues.add(new DiscoveryIssue(issue.kind(), basePointer + issue.pointer()));
        }
    }

    private void addMarkerEdges(List<DiscoveredEdge> edges, MarkerCollection collection, String basePointer) {
        for (MarkerReference reference : collection.references()) {
            checkRoom(edges);
            edges.add(classifyValue(basePointer + reference.pointer(), reference.value(), true));
        }
    }

    /** Called before each reference is classified, which can cost storage probes: the body may not end the export late or heavy. */
    private void checkRoom(List<DiscoveredEdge> edges) {
        deadline.check();
        if (edges.size() >= limits.maxEdges()) {
            throw ExportLimits.newExceededException("references");
        }
    }

    private static boolean isSchemaUnavailable(MarkerCollection collection) {
        return collection.issues().stream().anyMatch(issue -> issue.kind() == CollectionIssueKind.SCHEMA_UNAVAILABLE);
    }

    private DiscoveredEdge classifySchema(String pointer, ResourceTypes type, URI schemaId, boolean isUnavailable) {
        SchemaResolution resolution = schemaResolver.resolve(type, schemaId.toString());
        ResourceDescriptor descriptor = resolution.origin() == Origin.FILE ? ExportFileEntities.newSchemaDescriptor(type, schemaId.toString())
                : resolution.descriptor();
        return switch (resolution.outcome()) {
            case FOUND -> new DiscoveredEdge(pointer, isUnavailable ? EdgeStatus.SCHEMA_UNAVAILABLE : null, descriptor);
            case SKIPPED, NOT_FOUND -> newEdge(pointer, EdgeStatus.MISSING, null);
            case ORIGIN_UNKNOWN -> newEdge(pointer, EdgeStatus.ORIGIN_UNKNOWN, null);
        };
    }

    private DiscoveredEdge classifyInterceptor(String pointer, String value) {
        if (value == null || value.isBlank() || value.indexOf('/') >= 0) {
            return newEdge(pointer, EdgeStatus.MISSING, null);
        }
        return classifyResolution(pointer, resolver.resolve(ResourceTypes.INTERCEPTOR, value), EdgeStatus.MISSING);
    }

    private DiscoveredEdge classifyValue(String pointer, String value, boolean isMarked) {
        ClassifiedReference reference = ShortNameResolver.classify(value, encryptionService);
        EdgeStatus unresolved = isMarked ? EdgeStatus.UNRESOLVED_SHORT_NAME : EdgeStatus.MISSING;
        return switch (reference.form()) {
            case EXTERNAL_URL -> newEdge(pointer, EdgeStatus.EXTERNAL, null);
            case RESOURCE_URL -> classifyDescriptor(pointer, reference.descriptor());
            case SHORT_NAME -> classifyResolution(pointer, resolver.resolve(null, value), unresolved);
            case MALFORMED -> newEdge(pointer, unresolved, null);
        };
    }

    /** {@code icon_url} is a fixed field: a file of the platform is a file edge, an absolute URL is external, anything else is malformed. */
    private DiscoveredEdge classifyIcon(String value) {
        ClassifiedReference reference = ShortNameResolver.classify(value, encryptionService);
        if (reference.form() == ReferenceForm.EXTERNAL_URL) {
            return newEdge("/icon_url", EdgeStatus.EXTERNAL, null);
        }
        if (reference.form() == ReferenceForm.RESOURCE_URL && reference.descriptor().getType() == ResourceTypes.FILE) {
            return classifyDescriptor("/icon_url", reference.descriptor());
        }
        return newEdge("/icon_url", EdgeStatus.MISSING, null);
    }

    private static DiscoveredEdge classifySourceFolder() {
        return newEdge("/function/source_folder", EdgeStatus.UNSUPPORTED, null);
    }

    private DiscoveredEdge classifyDescriptor(String pointer, ResourceDescriptor descriptor) {
        return newEdge(pointer, isSupported(descriptor) ? null : EdgeStatus.UNSUPPORTED, descriptor);
    }

    private static boolean isSupported(ResourceDescriptor descriptor) {
        if (descriptor.isFolder()) {
            return false;
        }
        ResourceTypes type = (ResourceTypes) descriptor.getType();
        return ExportTypePolicy.isBound(type, descriptor.getBucketName())
                && ExportTypePolicy.isSupportedDependency(type, descriptor.getBucketLocation());
    }

    private DiscoveredEdge classifyResolution(String pointer, ShortNameResolution resolution, EdgeStatus statusWhenNothingHoldsTheName) {
        return switch (resolution.outcome()) {
            case FOUND -> newEdge(pointer, null,
                    resolution.origin() == Origin.PLATFORM ? resolvePlatformTarget(resolution) : resolveFileTarget(resolution));
            case SKIPPED -> newEdge(pointer, EdgeStatus.MISSING, null);
            case NOT_FOUND -> newEdge(pointer, statusWhenNothingHoldsTheName, null);
            case ORIGIN_UNKNOWN -> newEdge(pointer, EdgeStatus.ORIGIN_UNKNOWN, null);
        };
    }

    private static ResourceDescriptor resolveFileTarget(ShortNameResolution resolution) {
        return ExportFileEntities.newDescriptor(resolution.type(), resolution.name());
    }

    private static ResourceDescriptor resolvePlatformTarget(ShortNameResolution resolution) {
        return ResourceDescriptorFactory.fromDecoded(resolution.type(), ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION,
                resolution.name());
    }

    private static DiscoveredEdge newEdge(String pointer, @Nullable EdgeStatus status, @Nullable ResourceDescriptor target) {
        return new DiscoveredEdge(pointer, status, target);
    }
}
