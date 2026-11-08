package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.server.config.InvalidEntityRecord;
import com.epam.aidial.core.server.data.ExportResourceIdentity;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.DiscoveredEdge.EdgeStatus;
import com.epam.aidial.core.server.service.ExportDiscovery.DiscoveryIssue;
import com.epam.aidial.core.server.service.ShortNameResolution.Origin;
import com.epam.aidial.core.server.service.ShortNameResolver.ReferenceForm;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.validation.CollectionIssue;
import com.epam.aidial.core.server.validation.CollectionIssueKind;
import com.epam.aidial.core.server.validation.MarkerCollection;
import com.epam.aidial.core.server.validation.MarkerCollection.MarkerReference;
import com.epam.aidial.core.server.validation.MarkerKind;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.networknt.schema.JsonSchema;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class DeploymentEdgeDiscoveryTest {

    @Mock
    private ApplicationSchemaService schemaService;
    @Mock
    private CatalogSchemaService catalogSchemaService;
    @Mock
    private JsonSchema exportSchema;
    @Mock
    private EncryptionService encryptionService;
    @Mock
    private ResourceService resourceService;

    private final Config merged = new Config();
    private final Config file = new Config();
    private final Map<ResourceTypes, Map<String, InvalidEntityRecord>> invalid = new HashMap<>();
    private final Set<String> platformBlobs = new HashSet<>();
    private final Map<ResourceTypes, List<Pair<ResourceItemMetadata, String>>> schemaBlobs = new HashMap<>();

    @BeforeEach
    void setUp() {
        merged.setModels(new LinkedHashMap<>());
        merged.setApplications(new LinkedHashMap<>());
        merged.setToolsets(new LinkedHashMap<>());
        merged.setInterceptors(new LinkedHashMap<>());
        file.setModels(new LinkedHashMap<>());
        file.setApplications(new LinkedHashMap<>());
        file.setToolsets(new LinkedHashMap<>());
        file.setInterceptors(new LinkedHashMap<>());
        merged.setApplicationTypeSchemas(new LinkedHashMap<>());
        merged.setCatalogSchemas(new LinkedHashMap<>());
        file.setApplicationTypeSchemas(new LinkedHashMap<>());
        file.setCatalogSchemas(new LinkedHashMap<>());
        lenient().when(schemaService.buildExportSchema(any(URI.class), any(Config.class)))
                .thenReturn(new ApplicationSchemaService.ExportSchema(exportSchema, null));
        lenient().when(schemaService.collectMarkerReferences(any(Application.class), any(JsonSchema.class)))
                .thenReturn(new MarkerCollection(List.of(), List.of()));
        lenient().when(catalogSchemaService.collectMarkerReferences(any(Deployment.class), any(Config.class)))
                .thenReturn(new MarkerCollection(List.of(), List.of()));
        lenient().when(resourceService.getFolderMetadata(any(), any(), anyInt(), eq(true))).thenAnswer(invocation -> {
            ResourceDescriptor folder = invocation.getArgument(0);
            List<MetadataBase> items = schemaBlobs.getOrDefault((ResourceTypes) folder.getType(), List.of()).stream()
                    .map(blob -> (MetadataBase) blob.getKey()).toList();
            return new ResourceFolderMetadata(folder, items, null);
        });
        lenient().when(resourceService.getResource(any(ResourceDescriptor.class))).thenAnswer(invocation -> {
            ResourceDescriptor descriptor = invocation.getArgument(0);
            return schemaBlobs.getOrDefault((ResourceTypes) descriptor.getType(), List.of()).stream()
                    .filter(blob -> blob.getKey().getName().equals(descriptor.getName())).findFirst().map(Pair::getValue).orElse(null);
        });
        lenient().when(resourceService.hasResource(any())).thenAnswer(invocation -> {
            ResourceDescriptor descriptor = invocation.getArgument(0);
            return platformBlobs.contains(((ResourceTypes) descriptor.getType()).name() + "/" + descriptor.getName());
        });
    }

    private void givenPlatformModel(String name) {
        merged.getModels().put(name, new Model());
        platformBlobs.add("MODEL/" + name);
    }

    private void givenPlatformInterceptor(String name) {
        merged.getInterceptors().put(name, new Interceptor());
        platformBlobs.add("INTERCEPTOR/" + name);
    }

    private void givenPlatformApplication(String name) {
        merged.getApplications().put(name, new Application());
        platformBlobs.add("APPLICATION/" + name);
    }

    private void givenFileOnlyModel(String name) {
        Model model = new Model();
        merged.getModels().put(name, model);
        file.getModels().put(name, model);
    }

    private static MarkerReference resourceMarker(String pointer, String value) {
        return new MarkerReference(MarkerKind.RESOURCE, pointer, value);
    }

    private DeploymentEdgeDiscovery newDiscovery() {
        return new DeploymentEdgeDiscovery(merged, invalid, file, resourceService, schemaService, catalogSchemaService, encryptionService,
                ExportLimits.defaults(), ExportLimits.Deadline.unbounded());
    }

    private ExportDiscovery discover(Application application, MarkerReference... markers) {
        when(catalogSchemaService.collectMarkerReferences(application, merged))
                .thenReturn(new MarkerCollection(List.of(markers), List.of()));
        return newDiscovery().discover(application);
    }

    private DiscoveredEdge onlyEdgeOf(ExportDiscovery result) {
        assertEquals(1, result.edges().size());
        return result.edges().get(0);
    }

    // ---- markers of the catalog properties ------------------------------

    @Test
    public void testPlatformToolsetMarkerIsCandidateWithItsPointerAndIdentity() {
        DiscoveredEdge edge = onlyEdgeOf(discover(new Application(), resourceMarker("/tools/0/id", "toolsets/platform/t")));

        assertEquals("/catalog_properties/tools/0/id", edge.pointer());
        assertNull(edge.status(), "a candidate: the traversal still has to authorize and read it");
        assertEquals(ResourceTypes.TOOL_SET, edge.target().type());
        assertEquals("t", edge.target().name());
        assertEquals("platform/", edge.target().descriptor().getBucketLocation());
        assertNull(edge.valueKind());
    }

    @Test
    public void testExternalUrlIsListedWithoutTheRawValue() {
        DiscoveredEdge edge = onlyEdgeOf(discover(new Application(),
                resourceMarker("/url", "https://user:secret@example.com/x?token=abc")));

        assertEquals(EdgeStatus.EXTERNAL, edge.status());
        assertEquals(ReferenceForm.EXTERNAL_URL, edge.valueKind());
        assertNull(edge.target());
        assertFalse(edge.toString().contains("secret") || edge.toString().contains("token=abc"), edge.toString());
    }

    @Test
    public void testShortNameThatResolvesToPlatformModelIsCandidateWithItsOrigin() {
        givenPlatformModel("gpt-4o");

        DiscoveredEdge edge = onlyEdgeOf(discover(new Application(), resourceMarker("/deployment_id", "gpt-4o")));

        assertNull(edge.status());
        assertEquals(ResourceTypes.MODEL, edge.target().type());
        assertEquals(Origin.PLATFORM, edge.target().origin());
        assertEquals("models/platform/gpt-4o", edge.target().descriptor().getUrl());
        assertFalse(edge.target().hasShadowedEntity());
    }

    @Test
    public void testShortNameThatNothingHoldsIsUnresolvedAndCarriesNoRawValue() {
        DiscoveredEdge edge = onlyEdgeOf(discover(new Application(), resourceMarker("/deployment_id", "sk-abc123")));

        assertEquals(EdgeStatus.UNRESOLVED_SHORT_NAME, edge.status());
        assertEquals(ReferenceForm.SHORT_NAME, edge.valueKind());
        assertFalse(edge.toString().contains("sk-abc123"), edge.toString());
    }

    @Test
    public void testMalformedMarkerValueIsUnresolvedWithKindMalformed() {
        DiscoveredEdge edge = onlyEdgeOf(discover(new Application(), resourceMarker("/path", "files/b/a b")));

        assertEquals(EdgeStatus.UNRESOLVED_SHORT_NAME, edge.status());
        assertEquals(ReferenceForm.MALFORMED, edge.valueKind());
    }

    @Test
    public void testShortNameOfFileOnlyEntityIsIncludedWithOriginFile() {
        givenFileOnlyModel("file-model");

        DiscoveredEdge edge = onlyEdgeOf(discover(new Application(), resourceMarker("/deployment_id", "file-model")));

        assertNull(edge.status(), "the entity is exported from the file configuration");
        assertEquals(Origin.FILE, edge.target().origin());
        assertTrue(ExportFileEntities.isFile(edge.target().descriptor()), "the descriptor is the stand-in of a file entity, not a blob");
        assertFalse(edge.target().hasShadowedEntity());
    }

    @Test
    public void testFileEntityTakesPrecedenceOverPlatformBlobAndIsFlagged() {
        merged.getModels().put("m", new Model());
        Model fileModel = new Model();
        file.getModels().put("m", fileModel);
        platformBlobs.add("MODEL/m");

        DiscoveredEdge edge = onlyEdgeOf(discover(new Application(), resourceMarker("/deployment_id", "m")));

        assertNull(edge.status());
        assertEquals(Origin.FILE, edge.target().origin(), "the platform blob is not the effective entity");
        assertTrue(ExportFileEntities.isFile(edge.target().descriptor()));
        assertTrue(edge.target().hasShadowedEntity());
    }

    @Test
    public void testShortNameWhoseOriginCannotBeEstablishedIsListedAsOriginUnknown() {
        givenFileOnlyModel("m");
        platformBlobs.add("MODEL/m");

        DiscoveredEdge edge = onlyEdgeOf(discover(new Application(), resourceMarker("/deployment_id", "m")));

        assertEquals(EdgeStatus.ORIGIN_UNKNOWN, edge.status());
        assertNull(edge.target());
    }

    @Test
    public void testShortNameOfSkippedEntityIsMissingAndKeepsTheSkipReason() {
        invalid.put(ResourceTypes.MODEL, Map.of("models/platform/bad", new InvalidEntityRecord(
                "bad", "models/platform/bad", "missing interceptor", List.of(), "api", null)));

        DiscoveredEdge edge = onlyEdgeOf(discover(new Application(), resourceMarker("/deployment_id", "bad")));

        assertEquals(EdgeStatus.MISSING, edge.status());
        assertEquals(1, edge.skipped().size());
        assertEquals("missing interceptor", edge.skipped().get(0).reason());
    }

    @Test
    public void testSkippedApplicationOfTheSameNameIsReportedWhileTheModelIsCandidate() {
        givenPlatformModel("shared");
        invalid.put(ResourceTypes.APPLICATION, Map.of("applications/platform/shared", new InvalidEntityRecord(
                "shared", "applications/platform/shared", "duplicate", List.of(), "api", null)));

        DiscoveredEdge edge = onlyEdgeOf(discover(new Application(), resourceMarker("/deployment_id", "shared")));

        assertNull(edge.status());
        assertEquals(ResourceTypes.MODEL, edge.target().type());
        assertEquals(ResourceTypes.APPLICATION, edge.skipped().get(0).type());
    }

    @Test
    public void testShortNameHeldByTwoDeploymentMapsIsListedAsOriginUnknownWithoutTarget() {
        givenPlatformModel("shared");
        givenPlatformApplication("shared");
        Application application = new Application();
        application.setDependencies(List.of("shared"));

        DiscoveredEdge edge = onlyEdgeOf(discover(application));

        assertEquals(EdgeStatus.ORIGIN_UNKNOWN, edge.status());
        assertEquals(ReferenceForm.SHORT_NAME, edge.valueKind());
        assertNull(edge.target(), "no holder is chosen");
        assertEquals("/dependencies/0", edge.pointer());
    }

    @Test
    public void testInterceptorEntryResolvesTheInterceptorWhileModelHoldsTheSameName() {
        givenPlatformInterceptor("shared");
        givenPlatformModel("shared");
        Application application = new Application();
        application.setInterceptors(List.of("shared"));

        DiscoveredEdge edge = onlyEdgeOf(discover(application));

        assertNull(edge.status());
        assertEquals(ResourceTypes.INTERCEPTOR, edge.target().type());
    }

    // ---- icon_url and path safety ----------------------------------------------------------------

    private DiscoveredEdge iconEdge(String iconUrl) {
        Application application = new Application();
        application.setIconUrl(iconUrl);
        return onlyEdgeOf(discover(application));
    }

    @Test
    public void testFilesIconUrlIsSupportedFileEdge() {
        DiscoveredEdge edge = iconEdge("files/public/icons/a.png");

        assertEquals("/icon_url", edge.pointer());
        assertNull(edge.status());
        assertEquals(ResourceTypes.FILE, edge.target().type());
        assertEquals("public/", edge.target().descriptor().getBucketLocation());
    }

    @Test
    public void testAbsoluteIconUrlIsExternal() {
        DiscoveredEdge edge = iconEdge("https://example.com/icon.png");

        assertEquals("/icon_url", edge.pointer());
        assertEquals(EdgeStatus.EXTERNAL, edge.status());
        assertNull(edge.target());
    }

    @Test
    public void testAnyOtherIconUrlIsMissingWithValueKindMalformed() {
        for (String value : new String[] {"icon.png", "folder/icon.png", "applications/public/a", "files/public/a/../b", "files/public/a/%2e%2e/b"}) {
            DiscoveredEdge edge = iconEdge(value);

            assertEquals("/icon_url", edge.pointer(), value);
            assertEquals(EdgeStatus.MISSING, edge.status(), value);
            assertEquals(ShortNameResolver.ReferenceForm.MALFORMED, edge.valueKind(), value);
            assertNull(edge.target(), value);
        }
    }

    @Test
    public void testTraversalSegmentInReferenceIsMalformed() {
        Application application = new Application();
        application.setDependencies(List.of("applications/public/a/../b", "applications/public/a/%2e%2e/b"));

        List<DiscoveredEdge> edges = discover(application, resourceMarker("/m", "files/public/a/../b")).edges();

        assertEquals(3, edges.size());
        edges.forEach(edge -> {
            assertEquals(ShortNameResolver.ReferenceForm.MALFORMED, edge.valueKind(), edge.pointer());
            assertNull(edge.target(), edge.pointer());
        });
        assertEquals(EdgeStatus.MISSING, edges.get(0).status());
    }

    // ---- locations and public scope ---------------------------------------------------------------------------

    @Test
    public void testPublicApplicationToolSetPromptAndSkillDependenciesAreCandidatesAndConversationsAreUnsupported() {
        List<DiscoveredEdge> edges = discover(new Application(),
                resourceMarker("/app", "applications/public/folder/b__1.0.0"),
                resourceMarker("/toolset", "toolsets/public/t"),
                resourceMarker("/prompt", "prompts/public/p"),
                resourceMarker("/skill", "skills/public/s"),
                resourceMarker("/conversation", "conversations/public/c")).edges();

        assertNull(edges.get(0).status());
        assertEquals("public/", edges.get(0).target().descriptor().getBucketLocation());
        assertNull(edges.get(1).status(), "a public ToolSet is a candidate: the traversal still has to authorize and read it");
        assertEquals(ResourceTypes.TOOL_SET, edges.get(1).target().type());
        assertEquals("public/", edges.get(1).target().descriptor().getBucketLocation());
        assertNull(edges.get(2).status(), "a public Prompt is a candidate; the traversal still has to authorize and read it");
        assertEquals(ResourceTypes.PROMPT, edges.get(2).target().type());
        assertNull(edges.get(3).status(), "a public Skill is a candidate; the traversal still has to authorize and read its marker");
        assertEquals(ResourceTypes.SKILL, edges.get(3).target().type());
        assertEquals(EdgeStatus.UNSUPPORTED, edges.get(4).status(), "a Conversation is outside the table");
    }

    @Test
    public void testSpecialSublocationsAndUserBucketsAreUnsupportedWhateverTheType() {
        when(encryptionService.decrypt("deploymentsToken")).thenReturn("public/deployments/abc/");
        when(encryptionService.decrypt("reviewToken")).thenReturn("public/publications/p1/");
        when(encryptionService.decrypt("userToken")).thenReturn("Users/someone/");

        List<DiscoveredEdge> edges = discover(new Application(),
                resourceMarker("/a", "applications/deploymentsToken/x"),
                resourceMarker("/b", "applications/reviewToken/x"),
                resourceMarker("/c", "applications/userToken/x"),
                resourceMarker("/d", "files/userToken/x.txt")).edges();

        assertTrue(edges.stream().allMatch(edge -> edge.status() == EdgeStatus.UNSUPPORTED), edges.toString());
    }

    @Test
    public void testFolderMarkerIsUnsupported() {
        DiscoveredEdge edge = onlyEdgeOf(discover(new Application(), resourceMarker("/folder", "files/public/folder/")));

        assertEquals(EdgeStatus.UNSUPPORTED, edge.status());
    }

    // ---- fixed fields -------------------------------------------------------------------------------------------

    @Test
    public void testInterceptorsAreResolvedAmongInterceptorsOnly() {
        givenPlatformInterceptor("platform-i");
        Interceptor fileInterceptor = new Interceptor();
        merged.getInterceptors().put("file-i", fileInterceptor);
        file.getInterceptors().put("file-i", fileInterceptor);
        givenPlatformModel("a-model");
        Application application = new Application();
        application.setInterceptors(List.of("platform-i", "file-i", "gone", "a-model", "a/b"));

        List<DiscoveredEdge> edges = discover(application).edges();

        assertEquals(List.of("/interceptors/0", "/interceptors/1", "/interceptors/2", "/interceptors/3", "/interceptors/4"),
                edges.stream().map(DiscoveredEdge::pointer).toList());
        assertNull(edges.get(0).status());
        assertEquals(ResourceTypes.INTERCEPTOR, edges.get(0).target().type());
        assertNull(edges.get(1).status(), "a file-only interceptor is exported from the file configuration");
        assertEquals(EdgeStatus.MISSING, edges.get(2).status(), "a fixed field, not a marked one");
        assertEquals(EdgeStatus.MISSING, edges.get(3).status(), "a model is not an interceptor");
        assertEquals(ReferenceForm.MALFORMED, edges.get(4).valueKind());
    }

    @Test
    public void testDependenciesAcceptShortNamesAndUrlsAndMissingOnesAreMissing() {
        givenPlatformApplication("dep-app");
        Application application = new Application();
        application.setDependencies(List.of("dep-app", "applications/platform/other", "applications/public/p__1.0.0", "nobody"));

        List<DiscoveredEdge> edges = discover(application).edges();

        assertEquals("/dependencies/0", edges.get(0).pointer());
        assertNull(edges.get(0).status());
        assertEquals(Origin.PLATFORM, edges.get(0).target().origin());
        assertNull(edges.get(1).status(), "a platform Application by URL");
        assertNull(edges.get(2).status(), "a public Application by URL");
        assertEquals(EdgeStatus.MISSING, edges.get(3).status());
        assertEquals(ReferenceForm.SHORT_NAME, edges.get(3).valueKind());
    }

    @Test
    public void testFunctionSourceFolderIsAnUnsupportedFolderAndIsNeverResolved() {
        when(encryptionService.decrypt("deploymentsToken")).thenReturn("public/deployments/abc/");
        Application application = new Application();
        Application.Function function = new Application.Function();
        function.setSourceFolder("files/deploymentsToken/code/");
        application.setFunction(function);

        DiscoveredEdge edge = onlyEdgeOf(discover(application));

        assertEquals("/function/source_folder", edge.pointer());
        assertEquals(EdgeStatus.UNSUPPORTED, edge.status());
        assertNotNull(edge.target());
    }

    // ---- partial discovery and the snapshot ---------------------------------------------------------------------

    @Test
    public void testDiscoveryContinuesPastAnIncompleteMarkerCollectionAndReportsIt() {
        givenPlatformInterceptor("i");
        Application application = new Application();
        application.setInterceptors(List.of("i"));
        when(catalogSchemaService.collectMarkerReferences(application, merged)).thenReturn(new MarkerCollection(
                List.of(resourceMarker("/m", "toolsets/platform/t")),
                List.of(new CollectionIssue(CollectionIssueKind.INSTANCE_INVALID, "/other", "Violated keyword: type"))));

        ExportDiscovery result = newDiscovery().discover(application);

        assertEquals(2, result.edges().size(), "the other references are still discovered");
        assertFalse(result.isComplete());
    }

    @Test
    public void testSchemaIsCheckedOncePerSchemaIdWhateverTheNumberOfApplications() {
        DeploymentEdgeDiscovery discovery = newDiscovery();
        Application first = new Application();
        first.setApplicationTypeSchemaId(URI.create("https://dial.example/schemas/shared"));
        first.setApplicationProperties(Map.of("a", "1"));
        Application second = new Application();
        second.setApplicationTypeSchemaId(URI.create("https://dial.example/schemas/shared"));
        second.setApplicationProperties(Map.of("a", "2"));
        Application other = new Application();
        other.setApplicationTypeSchemaId(URI.create("https://dial.example/schemas/other"));
        other.setApplicationProperties(Map.of("a", "3"));

        discovery.discover(first);
        discovery.discover(second);
        discovery.discover(other);

        verify(schemaService, times(2)).buildExportSchema(any(URI.class), eq(merged));
        verify(schemaService, times(1)).buildExportSchema(URI.create("https://dial.example/schemas/shared"), merged);
        verify(schemaService, times(1)).buildExportSchema(URI.create("https://dial.example/schemas/other"), merged);
        // the references are collected per Application, against the schema built once
        verify(schemaService, times(3)).collectMarkerReferences(any(Application.class), eq(exportSchema));
    }

    // ---- schema edges ---------------------------------------------------------------------------------------------

    private static final String SCHEMA_ID = "https://dial.example/schemas/quickapps";
    private static final String CATALOG_ID = "https://dial.example/catalog/model";

    private static String schemaBody(String id) {
        return "{\"$id\":\"" + id + "\"}";
    }

    private void givenPlatformSchema(ResourceTypes type, String blobName, String id) {
        String body = schemaBody(id);
        if (type == ResourceTypes.APP_TYPE_SCHEMA) {
            merged.getApplicationTypeSchemas().put(id, body);
        } else {
            merged.getCatalogSchemas().put(id, body);
        }
        givenSchemaBlob(type, blobName, body);
    }

    private void givenSchemaBlob(ResourceTypes type, String blobName, String body) {
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(type, "platform", "platform/", blobName);
        schemaBlobs.computeIfAbsent(type, t -> new ArrayList<>()).add(Pair.of(new ResourceItemMetadata(descriptor), body));
    }

    private static Application applicationWithSchema(String id) {
        Application application = new Application();
        application.setApplicationTypeSchemaId(URI.create(id));
        return application;
    }

    @Test
    public void testPlatformSchemaIsCandidateIdentifiedByItsIdAndLocatedByItsBlob() {
        givenPlatformSchema(ResourceTypes.APP_TYPE_SCHEMA, "blob-name-differs", SCHEMA_ID);

        DiscoveredEdge edge = onlyEdgeOf(discover(applicationWithSchema(SCHEMA_ID)));

        assertEquals("/application_type_schema_id", edge.pointer());
        assertNull(edge.status());
        assertEquals(ResourceTypes.APP_TYPE_SCHEMA, edge.target().type());
        assertEquals(SCHEMA_ID, edge.target().name(), "a schema is identified by its body $id");
        assertEquals("blob-name-differs", edge.target().descriptor().getName(), "and located by its blob");
        assertEquals("platform/", edge.target().descriptor().getBucketLocation());
        assertEquals(Origin.PLATFORM, edge.target().origin());
    }

    @Test
    public void testMissingSchemaIsMissingAndCarriesNoIdentity() {
        DiscoveredEdge edge = onlyEdgeOf(discover(applicationWithSchema(SCHEMA_ID)));

        assertEquals(EdgeStatus.MISSING, edge.status());
        assertNull(edge.target());
        assertFalse(edge.toString().contains("dial.example"), edge.toString());
    }

    @Test
    public void testFileDefinedSchemaIsIncludedWithOriginFileAndTheStandInOfItsId() {
        String body = schemaBody(SCHEMA_ID);
        merged.getApplicationTypeSchemas().put(SCHEMA_ID, body);
        file.getApplicationTypeSchemas().put(SCHEMA_ID, body);

        DiscoveredEdge edge = onlyEdgeOf(discover(applicationWithSchema(SCHEMA_ID)));

        assertNull(edge.status(), "the schema is exported from the file configuration");
        assertEquals(Origin.FILE, edge.target().origin());
        assertTrue(ExportFileEntities.isFile(edge.target().descriptor()), "a stand-in descriptor, not a blob");
        assertEquals(ExportResourceIdentity.schemaDigest(SCHEMA_ID), edge.target().descriptor().getName());
        assertEquals(SCHEMA_ID, edge.target().name());
        assertFalse(edge.target().hasShadowedEntity());
    }

    @Test
    public void testFileSchemaWithValidBlobAndNoSkippedRecordIsOriginUnknown() {
        String body = schemaBody(SCHEMA_ID);
        merged.getApplicationTypeSchemas().put(SCHEMA_ID, body);
        file.getApplicationTypeSchemas().put(SCHEMA_ID, body);
        givenSchemaBlob(ResourceTypes.APP_TYPE_SCHEMA, "b", body);

        assertEquals(EdgeStatus.ORIGIN_UNKNOWN, onlyEdgeOf(discover(applicationWithSchema(SCHEMA_ID))).status());
    }

    @Test
    public void testFileSchemaTakesPrecedenceOverPlatformSchemaAndIsFlagged() {
        file.getApplicationTypeSchemas().put(SCHEMA_ID, "{\"$id\":\"" + SCHEMA_ID + "\",\"title\":\"file\"}");
        givenPlatformSchema(ResourceTypes.APP_TYPE_SCHEMA, "b", SCHEMA_ID);

        DiscoveredEdge edge = onlyEdgeOf(discover(applicationWithSchema(SCHEMA_ID)));

        assertNull(edge.status());
        assertEquals(Origin.FILE, edge.target().origin(), "the file entity is exported, the platform blob is not");
        assertTrue(ExportFileEntities.isFile(edge.target().descriptor()));
        assertTrue(edge.target().hasShadowedEntity());
    }

    @Test
    public void testSkippedSchemaBlobIsMissingAndKeepsTheSkipReason() {
        givenSchemaBlob(ResourceTypes.APP_TYPE_SCHEMA, "dup", schemaBody(SCHEMA_ID));
        invalid.put(ResourceTypes.APP_TYPE_SCHEMA, Map.of("schemas/platform/dup", new InvalidEntityRecord(
                "dup", "schemas/platform/dup", "duplicate $id", List.of(), "api", null)));

        DiscoveredEdge edge = onlyEdgeOf(discover(applicationWithSchema(SCHEMA_ID)));

        assertEquals(EdgeStatus.MISSING, edge.status());
        assertEquals("duplicate $id", edge.skipped().get(0).reason());
    }

    @Test
    public void testUnavailableRemoteSchemaMakesTheIncludableSchemaEdgeSchemaUnavailable() {
        givenPlatformSchema(ResourceTypes.APP_TYPE_SCHEMA, "b", SCHEMA_ID);
        Application application = applicationWithSchema(SCHEMA_ID);
        when(schemaService.buildExportSchema(URI.create(SCHEMA_ID), merged))
                .thenReturn(new ApplicationSchemaService.ExportSchema(null, CollectionIssueKind.SCHEMA_UNAVAILABLE));

        ExportDiscovery result = newDiscovery().discover(application);

        assertEquals(EdgeStatus.SCHEMA_UNAVAILABLE, onlyEdgeOf(result).status());
        assertNotNull(onlyEdgeOf(result).target(), "the schema itself was identified");
        assertFalse(result.isComplete(), "no marker of the application was evaluated");
    }

    @Test
    public void testCatalogSchemaIdFollowsTheGeneralMissingRule() {
        givenPlatformSchema(ResourceTypes.CATALOG_SCHEMA, "catalog-blob", CATALOG_ID);
        Application present = new Application();
        present.setCatalogSchemaId(URI.create(CATALOG_ID));
        Application absent = new Application();
        absent.setCatalogSchemaId(URI.create("https://dial.example/catalog/other"));

        DiscoveredEdge found = onlyEdgeOf(discover(present));
        DiscoveredEdge missing = onlyEdgeOf(discover(absent));

        assertEquals("/catalog_schema_id", found.pointer());
        assertNull(found.status());
        assertEquals(ResourceTypes.CATALOG_SCHEMA, found.target().type());
        assertEquals(EdgeStatus.MISSING, missing.status());
    }

    @Test
    public void testCatalogFilesAreSupportedWithTheirOwnPointer() {
        Application application = new Application();
        application.setCatalogSchemaId(URI.create(CATALOG_ID));
        givenPlatformSchema(ResourceTypes.CATALOG_SCHEMA, "catalog-blob", CATALOG_ID);
        when(catalogSchemaService.collectMarkerReferences(application, merged)).thenReturn(new MarkerCollection(
                List.of(new MarkerReference(MarkerKind.FILE, "/badge", "files/public/b.png")), List.of()));

        List<DiscoveredEdge> edges = newDiscovery().discover(application).edges();

        assertEquals(2, edges.size());
        assertEquals("/catalog_schema_id", edges.get(0).pointer());
        assertEquals("/catalog_properties/badge", edges.get(1).pointer());
        assertNull(edges.get(1).status());
    }

    @Test
    public void testFixedFieldsFollowTheSpecOrderAroundTheSchemaIds() {
        givenPlatformSchema(ResourceTypes.APP_TYPE_SCHEMA, "b", SCHEMA_ID);
        givenPlatformSchema(ResourceTypes.CATALOG_SCHEMA, "c", CATALOG_ID);
        givenPlatformInterceptor("i");
        Application application = applicationWithSchema(SCHEMA_ID);
        application.setCatalogSchemaId(URI.create(CATALOG_ID));
        application.setInterceptors(List.of("i"));
        application.setDependencies(List.of("applications/platform/d"));
        Application.Function function = new Application.Function();
        function.setSourceFolder("files/public/src/");
        application.setFunction(function);

        application.setApplicationProperties(Map.of("p", "toolsets/platform/a"));
        when(schemaService.collectMarkerReferences(application, exportSchema)).thenReturn(new MarkerCollection(
                List.of(resourceMarker("/p", "toolsets/platform/a")), List.of()));

        List<DiscoveredEdge> edges = discover(application, resourceMarker("/m", "toolsets/platform/t")).edges();

        assertEquals(List.of("/application_type_schema_id", "/interceptors/0", "/dependencies/0", "/catalog_schema_id",
                        "/function/source_folder", "/application_properties/p", "/catalog_properties/m"),
                edges.stream().map(DiscoveredEdge::pointer).toList());
    }

    @Test
    public void testApplicationPropertiesAreScannedAndItsMarkersBecomeEdgesAtTheirPointers() {
        Application application = applicationWithSchema(SCHEMA_ID);
        application.setApplicationProperties(Map.of("tool", "toolsets/platform/t", "file", "files/public/a.png", "model", "nope"));
        when(schemaService.collectMarkerReferences(application, exportSchema)).thenReturn(new MarkerCollection(List.of(
                resourceMarker("/tool", "toolsets/platform/t"),
                new MarkerReference(MarkerKind.FILE, "/file", "files/public/a.png"),
                resourceMarker("/model", "nope")), List.of()));

        ExportDiscovery result = newDiscovery().discover(application);

        assertEquals(List.of("/application_type_schema_id", "/application_properties/tool", "/application_properties/file",
                "/application_properties/model"), result.edges().stream().map(DiscoveredEdge::pointer).toList());
        assertEquals(ResourceTypes.TOOL_SET, result.edges().get(1).target().type());
        assertNull(result.edges().get(1).status());
        assertEquals(ResourceTypes.FILE, result.edges().get(2).target().type());
        assertEquals(EdgeStatus.UNRESOLVED_SHORT_NAME, result.edges().get(3).status());
        assertEquals(List.of(), result.issues());
        assertTrue(result.isComplete());
    }

    @Test
    public void testInstanceIssuesOfApplicationPropertiesMakeTheDiscoveryIncompleteAndAreAtTheirPointers() {
        Application application = applicationWithSchema(SCHEMA_ID);
        application.setApplicationProperties(Map.of("x", 1));
        when(schemaService.collectMarkerReferences(application, exportSchema)).thenReturn(new MarkerCollection(List.of(),
                List.of(new CollectionIssue(CollectionIssueKind.INSTANCE_INVALID, "/x", "Violated keyword: type"))));

        ExportDiscovery result = newDiscovery().discover(application);

        assertEquals(List.of(new DiscoveryIssue(CollectionIssueKind.INSTANCE_INVALID, "/application_properties/x")), result.issues());
        assertFalse(result.isComplete());
    }

    @Test
    public void testEdgeLimitCountsApplicationMarkersPlusCatalogMarkers() {
        Application application = applicationWithSchema(SCHEMA_ID);
        application.setApplicationProperties(Map.of("a", "1"));
        when(schemaService.collectMarkerReferences(application, exportSchema))
                .thenReturn(new MarkerCollection(List.of(shortNameMarkers(2)), List.of()));
        when(catalogSchemaService.collectMarkerReferences(application, merged))
                .thenReturn(new MarkerCollection(List.of(shortNameMarkers(2)), List.of()));

        HttpException failure = assertThrows(HttpException.class,
                () -> limitedDiscovery(3, ExportLimits.Deadline.unbounded()).discover(application));

        assertEquals(HttpStatus.REQUEST_ENTITY_TOO_LARGE, failure.getStatus());
    }

    @Test
    public void testSchemaIssueIsAtTheSchemaIdAndCatalogIssuesAreKeptWithPointersIntoTheBody() {
        Application application = applicationWithSchema(SCHEMA_ID);
        when(schemaService.buildExportSchema(URI.create(SCHEMA_ID), merged))
                .thenReturn(new ApplicationSchemaService.ExportSchema(null, CollectionIssueKind.SCHEMA_UNAVAILABLE));
        when(catalogSchemaService.collectMarkerReferences(application, merged)).thenReturn(new MarkerCollection(List.of(),
                List.of(new CollectionIssue(CollectionIssueKind.INSTANCE_INVALID, "/badge", "Violated keyword: type"))));

        ExportDiscovery result = newDiscovery().discover(application);

        assertEquals(List.of(
                        new DiscoveryIssue(CollectionIssueKind.SCHEMA_UNAVAILABLE, "/application_type_schema_id"),
                        new DiscoveryIssue(CollectionIssueKind.INSTANCE_INVALID, "/catalog_properties/badge")),
                result.issues());
        assertFalse(result.isComplete());
    }

    // ---- every Deployment: Model, Interceptor and ToolSet ----------------------------------------------------------

    private static Deployment deploymentOf(ResourceTypes type) {
        return switch (type) {
            case MODEL -> new Model();
            case INTERCEPTOR -> new Interceptor();
            case TOOL_SET -> new ToolSet();
            default -> new Application();
        };
    }

    private static final List<ResourceTypes> OTHER_DEPLOYMENTS = List.of(ResourceTypes.MODEL, ResourceTypes.INTERCEPTOR, ResourceTypes.TOOL_SET);

    @Test
    public void testModelInterceptorAndToolSetProduceTheEdgesOfEveryKind() {
        givenPlatformInterceptor("i");
        givenPlatformApplication("dep-app");
        givenPlatformSchema(ResourceTypes.CATALOG_SCHEMA, "catalog-blob", CATALOG_ID);
        for (ResourceTypes type : OTHER_DEPLOYMENTS) {
            Deployment deployment = deploymentOf(type);
            deployment.setInterceptors(List.of("i"));
            deployment.setDependencies(List.of("dep-app", "applications/platform/other"));
            deployment.setCatalogSchemaId(URI.create(CATALOG_ID));
            deployment.setIconUrl("files/public/icons/a.png");
            when(catalogSchemaService.collectMarkerReferences(deployment, merged)).thenReturn(new MarkerCollection(
                    List.of(resourceMarker("/tool", "toolsets/platform/t")), List.of()));

            List<DiscoveredEdge> edges = newDiscovery().discover(deployment).edges();

            assertEquals(List.of("/interceptors/0", "/dependencies/0", "/dependencies/1", "/catalog_schema_id", "/icon_url",
                            "/catalog_properties/tool"),
                    edges.stream().map(DiscoveredEdge::pointer).toList(), type.name());
            assertNull(edges.get(0).status(), type.name());
            assertEquals(ResourceTypes.INTERCEPTOR, edges.get(0).target().type(), type.name());
            assertEquals(Origin.PLATFORM, edges.get(1).target().origin(), type.name());
            assertEquals(ResourceTypes.APPLICATION, edges.get(2).target().type(), type.name());
            assertEquals(ResourceTypes.CATALOG_SCHEMA, edges.get(3).target().type(), type.name());
            assertEquals(ResourceTypes.FILE, edges.get(4).target().type(), type.name());
            assertEquals(ResourceTypes.TOOL_SET, edges.get(5).target().type(), type.name());
        }
    }

    @Test
    public void testBodyThatCannotBeBoundIsRefusedAndNothingOfItIsLogged() {
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            for (ResourceTypes type : List.of(ResourceTypes.APPLICATION, ResourceTypes.MODEL, ResourceTypes.INTERCEPTOR, ResourceTypes.TOOL_SET)) {
                // a string where an object is expected: the message of Jackson would quote the scalar
                IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                        () -> newDiscovery().discover(type, "{\"features\":\"SECRET-SCALAR\"}"), type.name());

                assertFalse(String.valueOf(thrown.getMessage()).contains("SECRET-SCALAR"), type.name());
            }
            for (ch.qos.logback.classic.spi.ILoggingEvent event : appender.list) {
                assertFalse(event.getFormattedMessage().contains("SECRET-SCALAR"), event.getFormattedMessage());
                assertTrue(event.getThrowableProxy() == null, "no exception is logged: " + event.getFormattedMessage());
            }
        } finally {
            root.detachAppender(appender);
        }
    }

    @Test
    public void testStoredBodiesAndTreesAreParsedByTheTypeOfTheDescriptor() {
        givenPlatformInterceptor("i");
        for (ResourceTypes type : OTHER_DEPLOYMENTS) {
            ExportDiscovery result = newDiscovery().discover(type, "{\"interceptors\":[\"i\"],\"icon_url\":\"files/public/a.png\"}");

            assertEquals(List.of("/interceptors/0", "/icon_url"), result.edges().stream().map(DiscoveredEdge::pointer).toList(), type.name());
        }
        com.fasterxml.jackson.databind.JsonNode tree = com.epam.aidial.core.server.util.ProxyUtil.MAPPER.createObjectNode()
                .set("interceptors", com.epam.aidial.core.server.util.ProxyUtil.MAPPER.createArrayNode().add("i"));

        ExportDiscovery fromTree = newDiscovery().discover(ResourceTypes.MODEL, tree);

        assertEquals(List.of("/interceptors/0"), fromTree.edges().stream().map(DiscoveredEdge::pointer).toList());
    }

    private DeploymentEdgeDiscovery limitedDiscovery(int maxEdges, ExportLimits.Deadline deadline) {
        ExportLimits limits = new ExportLimits(100, 500, 10, maxEdges, 4 * 1024 * 1024, 32 * 1024 * 1024, java.time.Duration.ofMinutes(1));
        return new DeploymentEdgeDiscovery(merged, invalid, file, resourceService, schemaService, catalogSchemaService,
                encryptionService, limits, deadline);
    }

    private static MarkerReference[] shortNameMarkers(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> resourceMarker("/p" + i, "name" + i))
                .toArray(MarkerReference[]::new);
    }

    @Test
    public void testFixedFieldsCountAgainstTheEdgeLimitToo() {
        Application application = new Application();
        application.setDependencies(List.of("a", "b", "c"));

        assertThrows(HttpException.class, () -> limitedDiscovery(2, ExportLimits.Deadline.unbounded()).discover(application));
        assertEquals(3, limitedDiscovery(3, ExportLimits.Deadline.unbounded()).discover(application).edges().size());

        Application marked = new Application();
        when(catalogSchemaService.collectMarkerReferences(marked, merged))
                .thenReturn(new MarkerCollection(List.of(shortNameMarkers(3)), List.of()));

        assertEquals(3, limitedDiscovery(3, ExportLimits.Deadline.unbounded()).discover(marked).edges().size(), "nothing is dropped at the limit");
    }

    @Test
    public void testExpiredDeadlineStopsTheEdges() {
        Application application = new Application();
        application.setDependencies(List.of("a", "b"));

        HttpException failure = assertThrows(HttpException.class,
                () -> limitedDiscovery(10, new ExportLimits.Deadline(System.nanoTime() - 1)).discover(application));

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, failure.getStatus());
    }
}
