package com.epam.aidial.core.server.service.dependency;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.ApplicationSchemaService;
import com.epam.aidial.core.server.service.CatalogSchemaService;
import com.epam.aidial.core.server.service.dependency.DiscoveredEdge.EdgeStatus;
import com.epam.aidial.core.server.service.dependency.DiscoveryResult.DiscoveryIssue;
import com.epam.aidial.core.server.service.dependency.ReferenceClassifier.ClassifiedReference;
import com.epam.aidial.core.server.service.dependency.ReferenceClassifier.ReferenceForm;
import com.epam.aidial.core.server.service.dependency.SchemaReferenceResolver.SchemaResolution;
import com.epam.aidial.core.server.service.dependency.ShortNameResolution.Origin;
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
import java.util.function.Predicate;

/**
 * Discovers the outgoing references of one Deployment and classifies each, without reading a body, authorizing, or checking that a
 * URL-form target exists. Short names go through the resolver, which reads the snapshot it was built from, so one walk looks every
 * name up in one generation.
 *
 * <p>Covered: {@code interceptors}, {@code dependencies}, {@code icon_url}, {@code catalog_schema_id}, the markers of
 * {@code catalog_properties}, and for an Application its application type schema id and the markers of
 * {@code application_properties}. A schema edge is {@code missing} when no schema has the id and otherwise follows
 * {@link SchemaReferenceResolver}; a schema that cannot be evaluated is still a candidate and is reported as an issue.
 */
final class DeploymentEdgeDiscovery {

    private static final String CATALOG_PROPERTIES_POINTER = "/catalog_properties";
    private static final String APPLICATION_PROPERTIES_POINTER = "/application_properties";

    private final Config snapshotConfig;
    private final ShortNameResolver resolver;
    private final SchemaReferenceResolver schemaResolver;
    private final ApplicationSchemaService schemaService;
    private final CatalogSchemaService catalogSchemaService;
    private final EncryptionService encryptionService;
    private final Predicate<ResourceDescriptor> supportsTarget;
    // one build per schema id: it can download the document of the runner, and many Applications share one schema
    private final Map<URI, ApplicationSchemaService.ReferenceSchema> builtSchemas = new HashMap<>();

    /**
     * @param snapshot       the configuration that all lookups read, so that one walk sees one generation
     * @param supportsTarget whether a reference to a resource in a location is a candidate at all, which the access policy decides
     */
    DeploymentEdgeDiscovery(ConfigSnapshot snapshot, ResourceService resourceService, ApplicationSchemaService schemaService,
                            CatalogSchemaService catalogSchemaService, EncryptionService encryptionService, PlatformSchemaScan platformSchemaScan,
                            Predicate<ResourceDescriptor> supportsTarget) {
        this.snapshotConfig = snapshot.merged();
        this.resolver = new ShortNameResolver(snapshot, resourceService);
        this.schemaResolver = new SchemaReferenceResolver(snapshot, platformSchemaScan);
        this.schemaService = schemaService;
        this.catalogSchemaService = catalogSchemaService;
        this.encryptionService = encryptionService;
        this.supportsTarget = supportsTarget;
    }

    /**
     * @param deployment only read, never changed
     */
    DiscoveryResult discover(Deployment deployment) {
        Application application = deployment instanceof Application candidate ? candidate : null;
        MarkerCollection markers = application == null ? MarkerCollection.EMPTY : collectApplicationMarkers(application);
        MarkerCollection catalogMarkers = catalogSchemaService.collectMarkerReferences(deployment, snapshotConfig);

        List<DiscoveredEdge> edges = new ArrayList<>();
        List<DiscoveryIssue> issues = new ArrayList<>();
        if (application != null && application.getApplicationTypeSchemaId() != null) {
            addSchemaEdge(edges, issues, "/application_type_schema_id", ResourceTypes.APP_TYPE_SCHEMA,
                    application.getApplicationTypeSchemaId(), markers);
        }
        List<String> interceptors = deployment.getInterceptors();
        for (int i = 0; interceptors != null && i < interceptors.size(); i++) {
            edges.add(classifyInterceptor("/interceptors/" + i, interceptors.get(i)));
        }
        List<String> dependencies = deployment.getDependencies();
        for (int i = 0; dependencies != null && i < dependencies.size(); i++) {
            edges.add(classifyValue("/dependencies/" + i, dependencies.get(i), false));
        }
        if (deployment.getCatalogSchemaId() != null) {
            addSchemaEdge(edges, issues, "/catalog_schema_id", ResourceTypes.CATALOG_SCHEMA, deployment.getCatalogSchemaId(), catalogMarkers);
        }
        if (deployment.getIconUrl() != null && !deployment.getIconUrl().isBlank()) {
            edges.add(classifyIcon(deployment.getIconUrl()));
        }

        addMarkerEdges(edges, markers, APPLICATION_PROPERTIES_POINTER);
        addMarkerEdges(edges, catalogMarkers, CATALOG_PROPERTIES_POINTER);
        addIssues(issues, markers, APPLICATION_PROPERTIES_POINTER);
        addIssues(issues, catalogMarkers, CATALOG_PROPERTIES_POINTER);
        return new DiscoveryResult(List.copyOf(edges), List.copyOf(issues));
    }

    /** The marked values of {@code application_properties} and the issues of its schema; without properties there is nothing to mark. */
    private MarkerCollection collectApplicationMarkers(Application application) {
        URI schemaId = application.getApplicationTypeSchemaId();
        Map<String, Object> properties = application.getApplicationProperties();
        if (schemaId == null || properties == null || properties.isEmpty()) {
            return MarkerCollection.EMPTY;
        }
        ApplicationSchemaService.ReferenceSchema built =
                builtSchemas.computeIfAbsent(schemaId, key -> schemaService.buildReferenceSchema(key, snapshotConfig));
        if (built.issue() != null) {
            return MarkerCollection.ofIssue(built.issue());
        }
        return schemaService.collectMarkerReferences(application, built.schema());
    }

    private static void addIssues(List<DiscoveryIssue> issues, MarkerCollection collection, String basePointer) {
        for (CollectionIssue issue : collection.issues()) {
            if (issue.kind() == CollectionIssueKind.INSTANCE_INVALID) {
                issues.add(new DiscoveryIssue(issue.kind(), basePointer + issue.pointer()));
            }
        }
    }

    private void addMarkerEdges(List<DiscoveredEdge> edges, MarkerCollection collection, String basePointer) {
        for (MarkerReference reference : collection.references()) {
            edges.add(classifyValue(basePointer + reference.pointer(), reference.value(), true));
        }
    }

    /**
     * A schema that exists is a candidate whether or not it can be evaluated: it does not depend on itself. One that cannot be evaluated is
     * an issue, because the references under its markers may have been missed.
     */
    private void addSchemaEdge(List<DiscoveredEdge> edges, List<DiscoveryIssue> issues, String pointer, ResourceTypes type, URI schemaId,
                               MarkerCollection collection) {
        DiscoveredEdge edge = classifySchema(pointer, type, schemaId);
        edges.add(edge);
        boolean isUnavailable = collection.issues().stream().anyMatch(issue -> issue.kind() == CollectionIssueKind.SCHEMA_UNAVAILABLE);
        if (edge.status() == null && isUnavailable) {
            issues.add(new DiscoveryIssue(CollectionIssueKind.SCHEMA_UNAVAILABLE, pointer));
        }
    }

    private DiscoveredEdge classifySchema(String pointer, ResourceTypes type, URI schemaId) {
        SchemaResolution resolution = schemaResolver.resolve(type, schemaId.toString());
        ResourceDescriptor descriptor = resolution.origin() == Origin.FILE ? FileConfigEntities.newSchemaDescriptor(type, schemaId.toString())
                : resolution.descriptor();
        return switch (resolution.outcome()) {
            case FOUND -> new DiscoveredEdge(pointer, null, descriptor);
            case SKIPPED, NOT_FOUND -> new DiscoveredEdge(pointer, EdgeStatus.MISSING, null);
            case ORIGIN_UNKNOWN -> new DiscoveredEdge(pointer, EdgeStatus.ORIGIN_UNKNOWN, null);
        };
    }

    private DiscoveredEdge classifyInterceptor(String pointer, String value) {
        if (value == null || value.isBlank() || value.indexOf('/') >= 0) {
            return new DiscoveredEdge(pointer, EdgeStatus.MISSING, null);
        }
        return classifyResolution(pointer, resolver.resolveInterceptor(value), EdgeStatus.MISSING);
    }

    private DiscoveredEdge classifyValue(String pointer, String value, boolean isMarked) {
        ClassifiedReference reference = ReferenceClassifier.classify(value, encryptionService);
        EdgeStatus unresolved = isMarked ? EdgeStatus.UNRESOLVED_SHORT_NAME : EdgeStatus.MISSING;
        return switch (reference.form()) {
            case EXTERNAL_URL -> new DiscoveredEdge(pointer, EdgeStatus.EXTERNAL, null);
            case RESOURCE_URL -> classifyDescriptor(pointer, reference.descriptor());
            case SHORT_NAME -> classifyResolution(pointer, resolver.resolve(value), unresolved);
            case MALFORMED -> new DiscoveredEdge(pointer, unresolved, null);
        };
    }

    /** {@code icon_url} is a fixed field: a file of the platform is a file edge, an absolute URL is external, anything else is malformed. */
    private DiscoveredEdge classifyIcon(String value) {
        ClassifiedReference reference = ReferenceClassifier.classify(value, encryptionService);
        if (reference.form() == ReferenceForm.EXTERNAL_URL) {
            return new DiscoveredEdge("/icon_url", EdgeStatus.EXTERNAL, null);
        }
        if (reference.form() == ReferenceForm.RESOURCE_URL && reference.descriptor().getType() == ResourceTypes.FILE) {
            return classifyDescriptor("/icon_url", reference.descriptor());
        }
        return new DiscoveredEdge("/icon_url", EdgeStatus.MISSING, null);
    }

    private DiscoveredEdge classifyDescriptor(String pointer, ResourceDescriptor descriptor) {
        return new DiscoveredEdge(pointer, supportsTarget.test(descriptor) ? null : EdgeStatus.UNSUPPORTED, descriptor);
    }

    private DiscoveredEdge classifyResolution(String pointer, ShortNameResolution resolution, EdgeStatus statusWhenNothingHoldsTheName) {
        return switch (resolution.outcome()) {
            case FOUND -> new DiscoveredEdge(pointer, null,
                    resolution.origin() == Origin.PLATFORM ? resolvePlatformTarget(resolution) : resolveFileTarget(resolution));
            case SKIPPED -> new DiscoveredEdge(pointer, EdgeStatus.MISSING, null);
            case NOT_FOUND -> new DiscoveredEdge(pointer, statusWhenNothingHoldsTheName, null);
            case ORIGIN_UNKNOWN -> new DiscoveredEdge(pointer, EdgeStatus.ORIGIN_UNKNOWN, null);
        };
    }

    private static ResourceDescriptor resolveFileTarget(ShortNameResolution resolution) {
        return FileConfigEntities.newDescriptor(resolution.type(), resolution.name());
    }

    private static ResourceDescriptor resolvePlatformTarget(ShortNameResolution resolution) {
        return ResourceDescriptorFactory.fromDecoded(resolution.type(), ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION,
                resolution.name());
    }
}

