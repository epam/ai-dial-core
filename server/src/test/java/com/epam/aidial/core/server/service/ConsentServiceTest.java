package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.config.ResourceDependency;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.consent.AdminConsentStatus;
import com.epam.aidial.core.server.data.consent.Consent;
import com.epam.aidial.core.server.data.consent.ConsentGrant;
import com.epam.aidial.core.server.data.consent.ReviewConsentResponse;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ConsentServiceTest {

    static class SortingNodeFactory extends JsonNodeFactory {
        @Override
        public ObjectNode objectNode() {
            return new ObjectNode(this, new TreeMap<>());
        }
    }

    private static final  ObjectMapper MAPPER = JsonMapper.builder()
            .nodeFactory(new SortingNodeFactory())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    @Mock
    private DeploymentService deploymentService;

    @Mock
    private ResourceService resourceService;

    @Mock
    ProxyContext context;

    @Mock
    private LongSupplier clock;

    @InjectMocks
    private ConsentService service;

    @Test
    public void testBuildConsent_WhenConsentIsMissed() {
        String jsonConfig = """
                {
                  "applications": {
                    "A": {
                       "dependencies": ["B", "C"]
                    },
                    "B": {
                       "dependencies": ["X"]
                    },
                    "C": {
                       "dependencies": ["Y"]
                    },
                    "X": {
                       "dependencies": ["Y"],
                       "features": {
                         "consentRequired": true
                       }
                    },
                    "Y": {
                       "dependencies": ["X"],
                       "features": {
                         "consentRequired": true
                       }
                    }
                  }
                }
                """;
        Config config = buildConfig(jsonConfig);
        when(context.getConfig()).thenReturn(config);
        when(context.getUserId()).thenReturn("user-sub");
        when(deploymentService.findDeployment(eq(context), anyString())).thenCallRealMethod();

        ReviewConsentResponse response = service.buildConsent(context, "A");
        assertNotNull(response);
        verifyJson("""
                {
                  "accepted" : false,
                  "consent" : {
                    "deployments" : {
                      "A" : {
                        "consentRequired" : false
                      },
                      "B" : {
                        "consentRequired" : false
                      },
                      "C" : {
                        "consentRequired" : false
                      },
                      "X" : {
                        "consentRequired" : true
                      },
                      "Y" : {
                        "consentRequired" : true
                      }
                    }
                  }
                }""", response);
    }

    @Test
    public void testBuildConsent_WhenConsentIsPartiallyAccepted() {
        String jsonConfig = """
                {
                  "applications": {
                    "A": {
                       "dependencies": ["B", "C"]
                    },
                    "B": {
                       "dependencies": ["X"]
                    },
                    "C": {
                       "dependencies": ["Y"]
                    },
                    "X": {
                       "dependencies": ["Y"],
                       "features": {
                         "consentRequired": true
                       }
                    },
                    "Y": {
                       "dependencies": ["X", "D"],
                       "features": {
                         "consentRequired": true
                       }
                    },
                    "D": {
                    }
                  }
                }
                """;
        Config config = buildConfig(jsonConfig);
        when(context.getConfig()).thenReturn(config);
        when(context.getUserId()).thenReturn("user-sub");
        when(deploymentService.findDeployment(eq(context), anyString())).thenCallRealMethod();
        when(resourceService.getResource(any(ResourceDescriptor.class))).thenReturn("""
               {
                   "deployments" : {
                      "A" : {
                        "consentRequired" : false
                      },
                      "B" : {
                        "consentRequired" : false
                      },
                      "C" : {
                        "consentRequired" : false
                      },
                      "X" : {
                        "consentRequired" : true
                      },
                      "Y" : {
                        "consentRequired" : true
                      }
                   }
               }
                """);

        ReviewConsentResponse response = service.buildConsent(context, "A");
        assertNotNull(response);
        verifyJson("""
                {
                  "accepted" : false,
                  "consent" : {
                    "deployments" : {
                      "A" : {
                        "consentRequired" : false
                      },
                      "B" : {
                        "consentRequired" : false
                      },
                      "C" : {
                        "consentRequired" : false
                      },
                      "D" : {
                        "consentRequired" : false
                      },
                      "X" : {
                        "consentRequired" : true
                      },
                      "Y" : {
                        "consentRequired" : true
                      }
                    }
                  }
                }""", response);
    }

    @Test
    public void testBuildConsent_WhenConsentIsFullyAccepted() {
        String jsonConfig = """
                {
                  "applications": {
                    "A": {
                       "dependencies": ["B", "C", "X"]
                    },
                    "B": {
                       "dependencies": ["X"]
                    },
                    "C": {
                       "dependencies": ["Y"]
                    },
                    "X": {
                       "dependencies": ["Y"],
                       "features": {
                         "consentRequired": true
                       }
                    },
                    "Y": {
                       "dependencies": ["X"],
                       "features": {
                         "consentRequired": true
                       }
                    }
                  }
                }
                """;
        Config config = buildConfig(jsonConfig);
        when(context.getConfig()).thenReturn(config);
        when(context.getUserId()).thenReturn("user-sub");
        when(deploymentService.findDeployment(eq(context), anyString())).thenCallRealMethod();
        when(resourceService.getResource(any(ResourceDescriptor.class))).thenReturn("""
               {
                   "deployments" : {
                      "A" : {
                        "consentRequired" : false
                      },
                      "B" : {
                        "consentRequired" : false
                      },
                      "C" : {
                        "consentRequired" : false
                      },
                      "X" : {
                        "consentRequired" : true
                      },
                      "Y" : {
                        "consentRequired" : true
                      }
                   }
               }
                """);

        ReviewConsentResponse response = service.buildConsent(context, "A");
        assertNotNull(response);
        verifyJson("""
                {
                  "accepted" : true
                }""", response);
    }

    @Test
    public void testBuildConsent_WhenNoAppsHaveConsentRequired() {
        String jsonConfig = """
                {
                  "applications": {
                    "A": {
                       "dependencies": ["B", "C", "X"]
                    },
                    "B": {
                       "dependencies": ["X"]
                    },
                    "C": {
                       "dependencies": ["Y"]
                    },
                    "X": {
                       "dependencies": ["Y"]
                    },
                    "Y": {
                       "dependencies": ["X"]
                    }
                  }
                }
                """;
        Config config = buildConfig(jsonConfig);
        when(context.getConfig()).thenReturn(config);
        when(deploymentService.findDeployment(eq(context), anyString())).thenCallRealMethod();

        ReviewConsentResponse response = service.buildConsent(context, "A");
        assertNotNull(response);
        verifyJson("""
                {
                  "accepted" : true
                }""", response);
    }

    @Test
    public void testAcceptConsent() {
        when(context.getUserId()).thenReturn("sub");

        service.acceptConsent(context, "id", new Consent());

        verify(resourceService).putResource(any(ResourceDescriptor.class), eq("{\"deployments\":{}}"), eq(EtagHeader.ANY));
    }

    @Test
    public void testVerifyUserConsent_WhenDeploymentDoesNotRequireConsent() {
        assertDoesNotThrow(() -> service.verifyUserConsent(context, new Application()));
    }

    @Test
    public void testVerifyUserConsent_WhenConsentIsMissed() {
        Application application = new Application();
        application.setName("app");
        Features features = new Features();
        features.setConsentRequired(true);
        application.setFeatures(features);

        ApiKeyData apiKeyData = new ApiKeyData();
        when(context.getApiKeyData()).thenReturn(apiKeyData);

        when(context.getUserId()).thenReturn("sub");

        assertThrows(PermissionDeniedException.class, () -> service.verifyUserConsent(context, application));
    }

    @Test
    public void testVerifyUserConsent_WhenConsentIsNotFound() {
        Application application = new Application();
        application.setName("C");
        Features features = new Features();
        features.setConsentRequired(true);
        application.setFeatures(features);

        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("key");
        apiKeyData.setExecutionPath(List.of("A", "B"));
        when(context.getApiKeyData()).thenReturn(apiKeyData);

        when(context.getUserId()).thenReturn("sub");

        assertThrows(PermissionDeniedException.class, () -> service.verifyUserConsent(context, application));
    }

    @Test
    public void testVerifyUserConsent_WhenTargetDeploymentIsNotAccepted() {
        Application application = new Application();
        application.setName("A");
        Features features = new Features();
        features.setConsentRequired(true);
        application.setFeatures(features);

        when(context.getUserId()).thenReturn("sub");

        ApiKeyData apiKeyData = new ApiKeyData();
        when(context.getApiKeyData()).thenReturn(apiKeyData);

        String jsonConsent = """
               {
                   "deployments" : {
                      "A" : {
                        "consentRequired" : false
                      },
                      "B" : {
                        "consentRequired" : false
                      },
                      "C" : {
                        "consentRequired" : false
                      },
                      "X" : {
                        "consentRequired" : true
                      },
                      "Y" : {
                        "consentRequired" : true
                      }
                   }
               }
                """;

        when(resourceService.getResource(any(ResourceDescriptor.class))).thenReturn(jsonConsent);

        assertThrows(PermissionDeniedException.class, () -> service.verifyUserConsent(context, application));
    }

    @Test
    public void testVerifyUserConsent_WhenDeploymentIsMissedInExecutionPath() {
        Application application = new Application();
        application.setName("X");
        Features features = new Features();
        features.setConsentRequired(true);
        application.setFeatures(features);

        when(context.getUserId()).thenReturn("sub");

        String jsonConsent = """
               {
                   "deployments" : {
                      "A" : {
                        "consentRequired" : false
                      },
                      "B" : {
                        "consentRequired" : false
                      },
                      "C" : {
                        "consentRequired" : false
                      },
                      "X" : {
                        "consentRequired" : true
                      },
                      "Y" : {
                        "consentRequired" : true
                      }
                   }
               }
                """;

        when(resourceService.getResource(any(ResourceDescriptor.class))).thenReturn(jsonConsent);
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setExecutionPath(List.of("A", "D"));
        when(context.getApiKeyData()).thenReturn(apiKeyData);

        assertThrows(PermissionDeniedException.class, () -> service.verifyUserConsent(context, application));
    }

    @Test
    public void testVerifyUserConsent_Success_RootIsCurrentDeployment() {
        Application application = new Application();
        application.setName("X");
        Features features = new Features();
        features.setConsentRequired(true);
        application.setFeatures(features);

        when(context.getUserId()).thenReturn("sub");

        String jsonConsent = """
               {
                   "deployments" : {
                      "A" : {
                        "consentRequired" : false
                      },
                      "B" : {
                        "consentRequired" : false
                      },
                      "C" : {
                        "consentRequired" : false
                      },
                      "X" : {
                        "consentRequired" : true
                      },
                      "Y" : {
                        "consentRequired" : true
                      }
                   }
               }
                """;

        when(resourceService.getResource(any(ResourceDescriptor.class))).thenReturn(jsonConsent);
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setExecutionPath(List.of("A", "B"));
        when(context.getApiKeyData()).thenReturn(apiKeyData);

        assertDoesNotThrow(() -> service.verifyUserConsent(context, application));
    }

    @Test
    public void testVerifyUserConsent_Success_RootIsInPath() {
        Application application = new Application();
        application.setName("X");
        Features features = new Features();
        features.setConsentRequired(true);
        application.setFeatures(features);

        when(context.getUserId()).thenReturn("sub");

        String jsonConsent = """
               {
                   "deployments" : {
                      "A" : {
                        "consentRequired" : false
                      },
                      "B" : {
                        "consentRequired" : false
                      },
                      "C" : {
                        "consentRequired" : false
                      },
                      "X" : {
                        "consentRequired" : true
                      },
                      "Y" : {
                        "consentRequired" : true
                      }
                   }
               }
                """;

        when(resourceService.getResource(any(ResourceDescriptor.class))).thenReturn(jsonConsent);

        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("key");
        apiKeyData.setExecutionPath(List.of("A", "B"));
        when(context.getApiKeyData()).thenReturn(apiKeyData);

        Application root = new Application();
        root.setName("A");

        assertDoesNotThrow(() -> service.verifyUserConsent(context, application));
    }

    /**
     * A deployment id is not a url. Here it is a config defined model name holding brackets, which are illegal
     * in a URI path, so validating it as one used to fail before any storage access.
     */
    @Test
    public void testAcceptConsent_DeploymentIdThatIsNotUriSafe() {
        when(context.getUserId()).thenReturn("sub");

        service.acceptConsent(context, "anthropic.claude-opus-4-8[1m]", new Consent());

        assertEquals("anthropic.claude-opus-4-8[1m]", capturePutDescriptor().getName());
    }

    /**
     * A custom application's id is an already encoded resource url, so the record path stays decoded the way it
     * has always been - the read side derives it from that very same name.
     */
    @Test
    public void testAcceptConsent_EncodedApplicationId() {
        when(context.getUserId()).thenReturn("sub");

        service.acceptConsent(context, "applications/buck/my%20app", new Consent());

        ResourceDescriptor descriptor = capturePutDescriptor();
        assertEquals("my app", descriptor.getName());
        assertEquals(List.of("applications", "buck"), descriptor.getParentFolders());
    }

    private ResourceDescriptor capturePutDescriptor() {
        ArgumentCaptor<ResourceDescriptor> captor = ArgumentCaptor.forClass(ResourceDescriptor.class);
        verify(resourceService).putResource(captor.capture(), eq("{\"deployments\":{}}"), eq(EtagHeader.ANY));
        return captor.getValue();
    }

    // ---- admin consent: the v1 gate ----

    @Test
    public void testBuildConsent_IncludesDeclaredResourcesRegardlessOfConsentRequiredFlag() {
        String jsonConfig = """
                {
                  "applications": {
                    "A": {
                       "resource_dependencies": [
                         {"kind": "dial.resourceLink", "link_id": "lnk_1", "target": {"path": "current-user/skills/"}, "access": ["WRITE"]}
                       ]
                    }
                  }
                }
                """;
        Config config = buildConfig(jsonConfig);
        when(context.getConfig()).thenReturn(config);
        when(context.getUserId()).thenReturn("user-sub");
        when(deploymentService.findDeployment(eq(context), anyString())).thenCallRealMethod();

        ReviewConsentResponse response = service.buildConsent(context, "A");

        // No deployment sets consentRequired, yet the declaration alone keeps the app off auto-accept (§6.1).
        verifyJson("""
                {
                  "accepted" : false,
                  "consent" : {
                    "deployments" : {
                      "A" : {
                        "consentRequired" : false
                      }
                    },
                    "resources" : [ {
                      "access" : [ "WRITE" ],
                      "url" : "current-user/skills/"
                    } ]
                  }
                }""", response);
    }

    @Test
    public void testGrantAdminConsent_StoresTheEnvelopeWithProvenanceInPublicAdminConsentRecord() {
        when(deploymentService.findDeployment(eq(context), eq("app"))).thenReturn(declaringApplication());
        when(context.getUserId()).thenReturn("admin-sub");
        when(clock.getAsLong()).thenReturn(1788394665564L);

        ConsentGrant grant = service.grantAdminConsent(context, "app");

        assertEquals(List.of(resourceEntry("current-user/skills/")), grant.getConsent().getResources());
        assertEquals("admin-sub", grant.getGrantedBy(), "provenance is server-stamped from the authenticated admin");
        assertEquals(1788394665564L, grant.getGrantedAt());
        ArgumentCaptor<ResourceDescriptor> captor = ArgumentCaptor.forClass(ResourceDescriptor.class);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(resourceService).putResource(captor.capture(), bodyCaptor.capture(), eq(EtagHeader.ANY));
        ResourceDescriptor descriptor = captor.getValue();
        assertEquals(ResourceTypes.ADMIN_CONSENT, descriptor.getType());
        assertTrue(descriptor.isPublic(), "the admin's yes is one record per app, always in the public bucket");
        // The stored body round-trips as the envelope — the snapshot sits inside it, stamps survive.
        ConsentGrant stored = ProxyUtil.convertToObject(bodyCaptor.getValue(), ConsentGrant.class);
        assertEquals(grant, stored);
    }

    @Test
    public void testGrantAdminConsent_RejectsApplicationWithoutDeclaration() {
        when(deploymentService.findDeployment(eq(context), eq("app"))).thenReturn(new Application());

        HttpException error = assertThrows(HttpException.class, () -> service.grantAdminConsent(context, "app"));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
    }

    @Test
    public void testIsAdminConsented_IsContentBoundToTheDeclaration() {
        when(resourceService.getResource(any(ResourceDescriptor.class))).thenReturn("""
                {"consent": {"resources": [{"url": "current-user/skills/", "access": ["WRITE"]}]},
                 "grantedBy": "admin-sub", "grantedAt": 1788394665564}
                """);

        assertTrue(service.isAdminConsented("app", declaration("current-user/skills/")));
        // any declaration change re-requires the grant — an extra entry, a reordered section
        assertFalse(service.isAdminConsented("app", declaration("current-user/skills/", "files/public/p/")));
        assertFalse(service.isAdminConsented("app", declaration("files/public/p/", "current-user/skills/")));
        // the grant's own provenance never participates in the binding — the same snapshot under a
        // different who/when still stands
        when(resourceService.getResource(any(ResourceDescriptor.class))).thenReturn("""
                {"consent": {"resources": [{"url": "current-user/skills/", "access": ["WRITE"]}]},
                 "grantedBy": "another-admin", "grantedAt": 9999999999999}
                """);
        assertTrue(service.isAdminConsented("app", declaration("current-user/skills/")));
    }

    @Test
    public void testIsAdminConsented_WhenNoRecordWasEverGranted() {
        when(resourceService.getResource(any(ResourceDescriptor.class))).thenReturn(null);

        assertFalse(service.isAdminConsented("app", declaration("current-user/skills/")));
    }

    @Test
    public void testDescribeAdminConsent_NeverGranted() {
        when(resourceService.getResource(any(ResourceDescriptor.class))).thenReturn(null);

        AdminConsentStatus status = service.describeAdminConsent("app", declaration("current-user/skills/"));

        assertFalse(status.isConsented());
        assertNull(status.getStale(), "stale is meaningless without a record");
        assertNull(status.getGrantedBy());
        assertNull(status.getGrantedAt());
        assertNull(status.getGrantedResources());
    }

    @Test
    public void testDescribeAdminConsent_LiveGrant() {
        when(resourceService.getResource(any(ResourceDescriptor.class))).thenReturn("""
                {"consent": {"resources": [{"url": "current-user/skills/", "access": ["WRITE"]}]},
                 "grantedBy": "admin-sub", "grantedAt": 1788394665564}
                """);

        AdminConsentStatus status = service.describeAdminConsent("app", declaration("current-user/skills/"));

        assertTrue(status.isConsented(), "consented means live right now — exactly what the resolver enforces");
        assertFalse(status.getStale());
        assertEquals("admin-sub", status.getGrantedBy());
        assertEquals(1788394665564L, status.getGrantedAt());
        assertEquals(List.of(resourceEntry("current-user/skills/")), status.getGrantedResources());
    }

    @Test
    public void testDescribeAdminConsent_StaleGrantKeepsTheLastApproval() {
        when(resourceService.getResource(any(ResourceDescriptor.class))).thenReturn("""
                {"consent": {"resources": [{"url": "current-user/skills/", "access": ["WRITE"]}]},
                 "grantedBy": "admin-sub", "grantedAt": 1788394665564}
                """);

        // the declaration changed since the grant — nothing resolves at runtime, and the status
        // must not report consented; the last approval stays visible for the panel's re-approve view
        AdminConsentStatus status = service.describeAdminConsent("app", declaration("files/public/p/"));

        assertFalse(status.isConsented(), "a stale record never reports consented — the runtime resolves nothing");
        assertTrue(status.getStale());
        assertEquals("admin-sub", status.getGrantedBy());
        assertEquals(1788394665564L, status.getGrantedAt());
        assertEquals(List.of(resourceEntry("current-user/skills/")), status.getGrantedResources());
    }

    @Test
    public void testDescribeAdminConsent_ResolvesTheApplicationAndReportsNonDeclarers() {
        // A non-declaring app is a legitimate "nothing consented" answer, not an error — reads inform.
        Application plain = new Application();
        plain.setName("app");
        when(deploymentService.findDeployment(eq(context), eq("app"))).thenReturn(plain);
        when(resourceService.getResource(any(ResourceDescriptor.class))).thenReturn(null);

        AdminConsentStatus status = service.describeAdminConsent(context, "app");

        assertFalse(status.isConsented());
        assertNull(status.getStale());
    }

    @Test
    public void testDescribeAdminConsent_LegacyBareConsentRecordFailsClosedWithoutThrowing() {
        // Records written by the pre-envelope commits of this branch store a bare Consent body at
        // the same key. Lenient reading must turn those into consent==null -> the fail-closed
        // stale path — never a throw, which would break the resolver (400 on every user call) and
        // make the record un-withdrawable.
        when(resourceService.getResource(any(ResourceDescriptor.class))).thenReturn("""
                {"resources": [{"url": "current-user/skills/", "access": ["WRITE"]}]}
                """);

        AdminConsentStatus status = service.describeAdminConsent("app", declaration("current-user/skills/"));

        assertFalse(status.isConsented(), "a legacy record never reads as consented — fail closed");
        assertTrue(status.getStale());
        assertEquals(List.of(), status.getGrantedResources());
        assertFalse(service.isAdminConsented("app", declaration("current-user/skills/")));
    }

    @Test
    public void testWithdrawAdminConsent_ReturnsTheWithdrawnGrantForTheAudit() {
        when(deploymentService.findDeployment(eq(context), eq("app"))).thenReturn(declaringApplication());
        when(resourceService.getResource(any(ResourceDescriptor.class))).thenReturn("""
                {"consent": {"resources": [{"url": "current-user/skills/", "access": ["WRITE"]}]},
                 "grantedBy": "admin-sub", "grantedAt": 1788394665564}
                """);

        ConsentGrant withdrawn = service.withdrawAdminConsent(context, "app");

        assertEquals(List.of(resourceEntry("current-user/skills/")), withdrawn.getConsent().getResources());
        assertEquals("admin-sub", withdrawn.getGrantedBy());
        verify(resourceService).deleteResource(any(ResourceDescriptor.class), eq(EtagHeader.ANY));
    }

    @Test
    public void testAdminConsentRecordIsKeyedByTheResolvedApplicationsCanonicalName() {
        // The resolver reads the record by the resolved application's name; the grant must write it
        // under the same identity — never the raw request id, or names with percent-sequences could
        // land one app's approval on another app's record.
        Application application = declaringApplication();
        application.setName("applications/public/gpt-helpe%2572");
        when(deploymentService.findDeployment(eq(context), eq("applications/public/gpt-helpe%2572")))
                .thenReturn(application);
        when(context.getUserId()).thenReturn("admin-sub");
        when(clock.getAsLong()).thenReturn(1L);

        service.grantAdminConsent(context, "applications/public/gpt-helpe%2572");

        ArgumentCaptor<ResourceDescriptor> captor = ArgumentCaptor.forClass(ResourceDescriptor.class);
        verify(resourceService).putResource(captor.capture(), anyString(), eq(EtagHeader.ANY));
        assertEquals("gpt-helpe%72", captor.getValue().getName());
    }

    private static Application declaringApplication() {
        Application application = new Application();
        application.setName("app");
        application.setResourceDependencies(declaration("current-user/skills/"));
        return application;
    }

    private static List<ResourceDependency> declaration(String... paths) {
        return Arrays.stream(paths)
                .map(path -> new ResourceDependency()
                        .setKind(ResourceDependency.KIND)
                        .setLinkId("lnk_" + path.hashCode())
                        .setTarget(new ResourceDependency.Target().setPath(path))
                        .setAccess(Set.of(ResourceAccessType.WRITE)))
                .toList();
    }

    private static Consent.ResourceEntry resourceEntry(String url) {
        Consent.ResourceEntry entry = new Consent.ResourceEntry();
        entry.setUrl(url);
        entry.setAccess(Set.of(ResourceAccessType.WRITE));
        return entry;
    }

    @SneakyThrows
    private static void verifyJson(String expected, Object actual) {
        String json = MAPPER.writeValueAsString(actual);
        assertEquals(expected, MAPPER.readTree(json).toPrettyString());
    }

    private static Config buildConfig(String payload) {
        return ProxyUtil.convertToObject(payload, Config.class);
    }

}
