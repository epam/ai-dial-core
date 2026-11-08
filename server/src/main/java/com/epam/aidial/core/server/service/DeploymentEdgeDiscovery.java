package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.server.config.InvalidEntityRecord;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.DiscoveredEdge.EdgeStatus;
import com.epam.aidial.core.server.service.DiscoveredEdge.EdgeTarget;
import com.epam.aidial.core.server.service.ExportDiscovery.DiscoveryIssue;
import com.epam.aidial.core.server.service.SchemaReferenceResolver.SchemaResolution;
import com.epam.aidial.core.server.service.ShortNameResolution.Origin;
import com.epam.aidial.core.server.service.ShortNameResolution.Skipped;
import com.epam.aidial.core.server.service.ShortNameResolver.ClassifiedReference;
import com.epam.aidial.core.server.service.ShortNameResolver.ReferenceForm;
import com.epam.aidial.core.server.service.config.ConfigEntityCodec;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.validation.CollectionIssue;
import com.epam.aidial.core.server.validation.CollectionIssueKind;
import com.epam.aidial.core.server.validation.MarkerCollection;
import com.epam.aidial.core.server.validation.MarkerCollection.MarkerReference;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Discovers the outgoing references of one Deployment (an Application, a Model, an Interceptor or a ToolSet) and classifies each without
 * reading a body, authorizing, or checking that a URL-form target exists: those steps follow for the candidates. Short names go through
 * the resolver, which reads the snapshot it was built from, so one export looks every name up in one generation.
 *
 * <p>Covered for every Deployment: {@code interceptors}, {@code dependencies}, {@code icon_url}, {@code catalog_schema_id} and the markers
 * of {@code catalog_properties}. An Application has more: its application type schema id and {@code function.source_folder}.
 * The properties of an Application are scanned for the markers of its schema: {@code application_properties} is collected against
 * the schema, and a value that a {@code dial:resource} or {@code dial:file} marker holds is a reference like any other. A schema edge is {@code missing} when no schema has
 * the id, {@code schema-unavailable} when the schema could not be evaluated, and otherwise follows the origin rule of
 * {@link SchemaReferenceResolver}.
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
     * @param merged          the merged {@code Config} that the resolvers and the schema lookups all read, so that one export looks
     *                        everything up in one generation
     * @param invalidEntities the skipped entities published with {@code merged}
     * @param fileConfig      the file-sourced {@code Config}, or {@code null} when the file store has none
     * @param limits          {@code maxEdges} bounds the references of one body: more fails the export, and nothing is cut
     * @param deadline        checked before every reference, and by the scan of the platform schemas
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
     * @param type the type of the Deployment: an Application, a Model, an Interceptor or a ToolSet
     * @param tree the representation of a Deployment of the file configuration
     */
    public ExportDiscovery discover(ResourceTypes type, JsonNode tree) {
        return discover(ConfigEntityCodec.treeToEntity(tree, classOf(type)));
    }

    /**
     * @param storedBody the Deployment as it was read, never empty
     */
    public ExportDiscovery discover(ResourceTypes type, String storedBody) {
        Deployment deployment;
        try {
            deployment = ProxyUtil.MAPPER.readValue(storedBody, classOf(type));
        } catch (JsonProcessingException e) {
            // not ProxyUtil.convertToObject: it logs the exception, whose message names a field and can quote a scalar of the body
            throw new IllegalArgumentException("Provided payload do not match required schema");
        }
        if (deployment == null) {
            throw new IllegalStateException("A stored resource has no content");
        }
        return discover(deployment);
    }

    /**
     * @param deployment the Deployment in its stored form
     */
    public ExportDiscovery discover(Deployment deployment) {
        Application application = deployment instanceof Application candidate ? candidate : null;
        MarkerCollection markers = application == null ? NO_MARKERS : collectApplicationMarkers(application);
        MarkerCollection catalogMarkers = catalogSchemaService.collectMarkerReferences(deployment, snapshotConfig);

        if (markers.references().size() + catalogMarkers.references().size() > limits.maxEdges()) {
            // before any is classified, so that a body of this kind costs no storage probe
            throw ExportLimits.exceeded("references");
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
            edges.add(classifySourceFolder(application.getFunction().getSourceFolder()));
        }

        addMarkerEdges(edges, markers, APPLICATION_PROPERTIES_POINTER);
        addMarkerEdges(edges, catalogMarkers, CATALOG_PROPERTIES_POINTER);
        return toDiscovery(List.copyOf(edges), markers, catalogMarkers);
    }

    /**
     * The marked values of {@code application_properties} and the issues of its schema. The schema is built once per schema id and
     * export; the properties of each Application are collected against it. An Application without a schema or without properties has
     * nothing to collect.
     */
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

    private static Class<? extends Deployment> classOf(ResourceTypes type) {
        return switch (type) {
            case APPLICATION -> Application.class;
            case MODEL -> Model.class;
            case INTERCEPTOR -> Interceptor.class;
            case TOOL_SET -> ToolSet.class;
            default -> throw new IllegalArgumentException("The references of this type are not examined");
        };
    }

    /**
     * The issues of the collections of both property sets as issues of the Application body. An issue that says that the schema cannot
     * be used is at the pointer of the schema id; any other issue of the Application properties and every issue of the catalog
     * properties is at its pointer inside the body.
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
            throw ExportLimits.exceeded("references");
        }
    }

    private static boolean isSchemaUnavailable(MarkerCollection collection) {
        return collection.issues().stream().anyMatch(issue -> issue.kind() == CollectionIssueKind.SCHEMA_UNAVAILABLE);
    }

    private DiscoveredEdge classifySchema(String pointer, ResourceTypes type, URI schemaId, boolean isUnavailable) {
        SchemaResolution resolution = schemaResolver.resolve(type, schemaId.toString());
        List<Skipped> skipped = resolution.skipped();
        ResourceDescriptor descriptor = resolution.origin() == Origin.FILE ? ExportFileEntities.descriptorOfSchema(type, schemaId.toString())
                : resolution.descriptor();
        EdgeTarget target = new EdgeTarget(type, schemaId.toString(), descriptor, resolution.origin(), resolution.hasShadowedEntity());
        return switch (resolution.outcome()) {
            case FOUND -> new DiscoveredEdge(pointer, isUnavailable ? EdgeStatus.SCHEMA_UNAVAILABLE : null, null, target, skipped);
            case SKIPPED, NOT_FOUND -> edge(pointer, EdgeStatus.MISSING, null, null, skipped);
            case ORIGIN_UNKNOWN -> edge(pointer, EdgeStatus.ORIGIN_UNKNOWN, null, null, skipped);
        };
    }

    private DiscoveredEdge classifyInterceptor(String pointer, String value) {
        if (value == null || value.isBlank() || value.indexOf('/') >= 0) {
            return edge(pointer, EdgeStatus.MISSING, ReferenceForm.MALFORMED, null, List.of());
        }
        return fromResolution(pointer, resolver.resolve(ResourceTypes.INTERCEPTOR, value),
                EdgeStatus.MISSING);
    }

    private DiscoveredEdge classifyValue(String pointer, String value, boolean isMarked) {
        ClassifiedReference reference = ShortNameResolver.classify(value, encryptionService);
        EdgeStatus unresolved = isMarked ? EdgeStatus.UNRESOLVED_SHORT_NAME : EdgeStatus.MISSING;
        return switch (reference.form()) {
            case EXTERNAL_URL -> edge(pointer, EdgeStatus.EXTERNAL, reference.form(), null, List.of());
            case RESOURCE_URL -> classifyDescriptor(pointer, reference.descriptor());
            case SHORT_NAME -> fromResolution(pointer, resolver.resolve(null, value), unresolved);
            case MALFORMED -> edge(pointer, unresolved, reference.form(), null, List.of());
        };
    }

    /**
     * {@code icon_url} is a fixed field: a file of the platform is a file edge, an absolute URL is external, and any other value is a
     * missing reference of the kind malformed. Nothing is fetched.
     */
    private DiscoveredEdge classifyIcon(String value) {
        ClassifiedReference reference = ShortNameResolver.classify(value, encryptionService);
        if (reference.form() == ReferenceForm.EXTERNAL_URL) {
            return edge("/icon_url", EdgeStatus.EXTERNAL, reference.form(), null, List.of());
        }
        if (reference.form() == ReferenceForm.RESOURCE_URL && reference.descriptor().getType() == ResourceTypes.FILE) {
            return classifyDescriptor("/icon_url", reference.descriptor());
        }
        return edge("/icon_url", EdgeStatus.MISSING, ReferenceForm.MALFORMED, null, List.of());
    }

    private DiscoveredEdge classifySourceFolder(String value) {
        ClassifiedReference reference = ShortNameResolver.classify(value, encryptionService);
        EdgeTarget target = reference.descriptor() == null ? null : targetOf(reference.descriptor());
        return edge("/function/source_folder", EdgeStatus.UNSUPPORTED,
                target == null ? reference.form() : null, target, List.of());
    }

    private DiscoveredEdge classifyDescriptor(String pointer, ResourceDescriptor descriptor) {
        return edge(pointer, isSupported(descriptor) ? null : EdgeStatus.UNSUPPORTED, null, targetOf(descriptor), List.of());
    }

    private static boolean isSupported(ResourceDescriptor descriptor) {
        if (descriptor.isFolder()) {
            return false;
        }
        ResourceTypes type = (ResourceTypes) descriptor.getType();
        return ExportTypePolicy.isBound(type, descriptor.getBucketName())
                && ExportTypePolicy.isSupportedDependency(type, descriptor.getBucketLocation());
    }

    private static EdgeTarget targetOf(ResourceDescriptor descriptor) {
        return new EdgeTarget((ResourceTypes) descriptor.getType(), descriptor.getName(), descriptor, null, false);
    }

    private DiscoveredEdge fromResolution(String pointer, ShortNameResolution resolution,
                                          EdgeStatus statusWhenNothingHoldsTheName) {
        List<Skipped> skipped = resolution.skipped();
        return switch (resolution.outcome()) {
            case FOUND -> edge(pointer, null, null,
                    resolution.origin() == Origin.PLATFORM ? platformTarget(resolution) : fileTarget(resolution), skipped);
            case SKIPPED -> edge(pointer, EdgeStatus.MISSING, ReferenceForm.SHORT_NAME, null, skipped);
            case NOT_FOUND -> edge(pointer, statusWhenNothingHoldsTheName, ReferenceForm.SHORT_NAME, null, skipped);
            case ORIGIN_UNKNOWN -> edge(pointer, EdgeStatus.ORIGIN_UNKNOWN, ReferenceForm.SHORT_NAME, null, skipped);
        };
    }

    private static EdgeTarget fileTarget(ShortNameResolution resolution) {
        return new EdgeTarget(resolution.type(), resolution.name(), ExportFileEntities.descriptorOf(resolution.type(), resolution.name()),
                Origin.FILE, resolution.hasShadowedEntity());
    }

    private static EdgeTarget platformTarget(ShortNameResolution resolution) {
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(resolution.type(),
                ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION, resolution.name());
        return new EdgeTarget(resolution.type(), resolution.name(), descriptor, Origin.PLATFORM, resolution.hasShadowedEntity());
    }

    private static DiscoveredEdge edge(String pointer, @Nullable EdgeStatus status, @Nullable ReferenceForm valueKind,
                                       @Nullable EdgeTarget target, List<Skipped> skipped) {
        return new DiscoveredEdge(pointer, status, valueKind, target, skipped);
    }
}
