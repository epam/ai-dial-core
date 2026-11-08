package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.InterfaceType;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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

    @BeforeEach
    void emptyConfig() {
        // the admin-consent tests resolve dynamic applications: nothing in the config
        lenient().when(context.getConfig()).thenReturn(new Config());
    }

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
    void interfaceCanDisableConsentWithoutAffectingOtherInterfaces() {
        Application application = new Application();
        application.setName("app");
        Features features = new Features();
        features.setConsentRequired(true);
        application.setFeatures(features);
        Features overrides = new Features();
        overrides.setConsentRequired(false);
        DeploymentInterface declared = new DeploymentInterface();
        declared.setFeatures(overrides);
        application.setInterfaces(Map.of(InterfaceType.OPENAI_CHAT_COMPLETIONS.getValue(), declared));

        assertDoesNotThrow(() -> service.verifyUserConsent(context, application, InterfaceType.OPENAI_CHAT_COMPLETIONS));

        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(context.getUserId()).thenReturn("sub");
        assertThrows(PermissionDeniedException.class,
                () -> service.verifyUserConsent(context, application, InterfaceType.OPENAI_RESPONSES));
    }

    @Test
    void consentReviewIncludesInterfaceRequirements() {
        Application application = new Application();
        application.setName("app");
        Features overrides = new Features();
        overrides.setConsentRequired(true);
        DeploymentInterface declared = new DeploymentInterface();
        declared.setFeatures(overrides);
        application.setInterfaces(Map.of(InterfaceType.OPENAI_CHAT_COMPLETIONS.getValue(), declared));
        when(deploymentService.findDeployment(context, "app")).thenReturn(application);
        when(context.getUserId()).thenReturn("sub");

        ReviewConsentResponse review = service.buildConsent(context, "app");

        assertFalse(review.accepted());
        assertTrue(review.consent().getDeployments().get("app").isConsentRequired());
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        assertThrows(PermissionDeniedException.class,
                () -> service.verifyUserConsent(context, application, InterfaceType.OPENAI_CHAT_COMPLETIONS));
        assertDoesNotThrow(() -> service.verifyUserConsent(context, application, InterfaceType.OPENAI_RESPONSES));
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


    @Test
    public void testBuildConsent_IncludesDeclaredResourcesRegardlessOfConsentRequiredFlag() {
        String jsonConfig = """
                {
                  "applications": {
                    "A": {
                       "resource_dependencies": [
                         {"kind": "dial.resourceLink", "link_id": "lnk_1", "target": {"url": "skills/{current-user}/"}, "access": ["WRITE"]}
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

        // No deployment sets consentRequired, yet the declaration alone keeps the app off auto-accept.
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
                      "url" : "skills/{current-user}/"
                    } ]
                  }
                }""", response);
    }

    @Test
    public void testGrantAdminConsent_StoresTheEnvelopeWithProvenanceInPublicAdminConsentRecord() {
        when(deploymentService.findDeployment(eq(context), eq("app"))).thenReturn(declaringApplication());
        when(context.getInitiatorId()).thenReturn("admin-sub");
        when(clock.getAsLong()).thenReturn(1788394665564L);

        ConsentGrant grant = service.grantAdminConsent(context, "app", List.of(resourceEntry("skills/{current-user}/")));

        assertEquals(List.of(resourceEntry("skills/{current-user}/")), grant.getConsent().getResources());
        assertEquals("admin-sub", grant.getGrantedBy(), "provenance is server-stamped from the authenticated admin");
        assertEquals(1788394665564L, grant.getGrantedAt());
        ArgumentCaptor<ResourceDescriptor> captor = ArgumentCaptor.forClass(ResourceDescriptor.class);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(resourceService).putResource(captor.capture(), bodyCaptor.capture(), eq(EtagHeader.ANY));
        ResourceDescriptor descriptor = captor.getValue();
        assertEquals(ResourceTypes.ADMIN_CONSENT, descriptor.getType());
        assertTrue(descriptor.isPublic(), "the admin's yes is one record per app, always in the public bucket");
        ConsentGrant stored = ProxyUtil.convertToObject(bodyCaptor.getValue(), ConsentGrant.class);
        assertEquals(grant, stored);
    }

    @Test
    public void testGrantAdminConsent_StampsTheApiKeyProjectForAnApiKeyAdmin() {
        when(deploymentService.findDeployment(eq(context), eq("app"))).thenReturn(declaringApplication());
        when(context.getInitiatorId()).thenReturn("automation-project");

        ConsentGrant grant = service.grantAdminConsent(context, "app", List.of(resourceEntry("skills/{current-user}/")));

        assertEquals("automation-project", grant.getGrantedBy());
    }

    @Test
    public void testGrantAdminConsent_RejectsApplicationWithoutDeclaration() {
        when(deploymentService.findDeployment(eq(context), eq("app"))).thenReturn(new Application());

        HttpException error = assertThrows(HttpException.class, () -> service.grantAdminConsent(context, "app", List.of()));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
    }

    @Test
    public void testGrantAdminConsent_RejectsDeclarationChangedSinceTheReview() {
        when(deploymentService.findDeployment(eq(context), eq("app")))
                .thenReturn(declaringApplication("skills/{current-user}/", "files/public/p/"));

        HttpException error = assertThrows(HttpException.class,
                () -> service.grantAdminConsent(context, "app", List.of(resourceEntry("skills/{current-user}/"))));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        verify(resourceService, never()).putResource(any(ResourceDescriptor.class), anyString(), any(EtagHeader.class));
    }

    @Test
    public void testIsAdminConsented_IsContentBoundToTheDeclaration() {
        when(resourceService.getResource(any(ResourceDescriptor.class))).thenReturn("""
                {"consent": {"resources": [{"url": "skills/{current-user}/", "access": ["WRITE"]}]},
                 "grantedBy": "admin-sub", "grantedAt": 1788394665564}
                """);

        assertTrue(service.isAdminConsented("app", declaration("skills/{current-user}/")));
        // an extra entry or a reordered section re-requires the grant
        assertFalse(service.isAdminConsented("app", declaration("skills/{current-user}/", "files/public/p/")));
        assertFalse(service.isAdminConsented("app", declaration("files/public/p/", "skills/{current-user}/")));
        // provenance is not part of the binding: the same snapshot under another who/when still stands
        when(resourceService.getResource(any(ResourceDescriptor.class))).thenReturn("""
                {"consent": {"resources": [{"url": "skills/{current-user}/", "access": ["WRITE"]}]},
                 "grantedBy": "another-admin", "grantedAt": 9999999999999}
                """);
        assertTrue(service.isAdminConsented("app", declaration("skills/{current-user}/")));
    }

    @Test
    public void testIsAdminConsented_WhenNoRecordWasEverGranted() {
        when(resourceService.getResource(any(ResourceDescriptor.class))).thenReturn(null);

        assertFalse(service.isAdminConsented("app", declaration("skills/{current-user}/")));
    }

    @Test
    public void testDescribeAdminConsent_NeverGranted() {
        when(resourceService.getResource(any(ResourceDescriptor.class))).thenReturn(null);

        when(deploymentService.findDeployment(eq(context), eq("app"))).thenReturn(declaringApplication("skills/{current-user}/"));
        AdminConsentStatus status = service.describeAdminConsent(context, "app");

        assertFalse(status.isConsented());
        assertNull(status.getStale(), "stale is meaningless without a record");
        assertNull(status.getGrantedBy());
        assertNull(status.getGrantedAt());
        assertNull(status.getGrantedResources());
    }

    @Test
    public void testDescribeAdminConsent_LiveGrant() {
        when(resourceService.getResource(any(ResourceDescriptor.class))).thenReturn("""
                {"consent": {"resources": [{"url": "skills/{current-user}/", "access": ["WRITE"]}]},
                 "grantedBy": "admin-sub", "grantedAt": 1788394665564}
                """);

        when(deploymentService.findDeployment(eq(context), eq("app"))).thenReturn(declaringApplication("skills/{current-user}/"));
        AdminConsentStatus status = service.describeAdminConsent(context, "app");

        assertTrue(status.isConsented(), "consented means live right now — exactly what the resolver enforces");
        assertFalse(status.getStale());
        assertEquals("admin-sub", status.getGrantedBy());
        assertEquals(1788394665564L, status.getGrantedAt());
        assertEquals(List.of(resourceEntry("skills/{current-user}/")), status.getGrantedResources());
    }

    @Test
    public void testDescribeAdminConsent_StaleGrantKeepsTheLastApproval() {
        when(resourceService.getResource(any(ResourceDescriptor.class))).thenReturn("""
                {"consent": {"resources": [{"url": "skills/{current-user}/", "access": ["WRITE"]}]},
                 "grantedBy": "admin-sub", "grantedAt": 1788394665564}
                """);

        // declaration changed since the grant: not consented, the last approval still shown
        when(deploymentService.findDeployment(eq(context), eq("app"))).thenReturn(declaringApplication("files/public/p/"));
        AdminConsentStatus status = service.describeAdminConsent(context, "app");

        assertFalse(status.isConsented(), "a stale record never reports consented — the runtime resolves nothing");
        assertTrue(status.getStale());
        assertEquals("admin-sub", status.getGrantedBy());
        assertEquals(1788394665564L, status.getGrantedAt());
        assertEquals(List.of(resourceEntry("skills/{current-user}/")), status.getGrantedResources());
    }

    @Test
    public void testDescribeAdminConsent_ResolvesTheApplicationAndReportsNonDeclarers() {
        // a non-declaring app is a "nothing consented" answer, not an error
        Application plain = new Application();
        plain.setName("app");
        when(deploymentService.findDeployment(eq(context), eq("app"))).thenReturn(plain);
        when(resourceService.getResource(any(ResourceDescriptor.class))).thenReturn(null);

        AdminConsentStatus status = service.describeAdminConsent(context, "app");

        assertFalse(status.isConsented());
        assertNull(status.getStale());
    }

    @Test
    public void testRecordWithoutSnapshotFailsClosedAndCanBeWithdrawn() {
        // a stored record without a snapshot is never consented, reads as stale, and can still be withdrawn
        when(resourceService.getResource(any(ResourceDescriptor.class))).thenReturn("""
                {"resources": [{"url": "skills/{current-user}/", "access": ["WRITE"]}]}
                """);

        when(deploymentService.findDeployment(eq(context), eq("app"))).thenReturn(declaringApplication("skills/{current-user}/"));
        AdminConsentStatus status = service.describeAdminConsent(context, "app");

        assertFalse(status.isConsented(), "a record without a snapshot never reads as consented — fail closed");
        assertTrue(status.getStale());
        assertEquals(List.of(), status.getGrantedResources());
        assertFalse(service.isAdminConsented("app", declaration("skills/{current-user}/")));

        assertNull(service.withdrawAdminConsent(context, "app").getConsent());
        verify(resourceService).deleteResource(any(ResourceDescriptor.class), eq(EtagHeader.ANY));
    }

    @Test
    public void testUnreadableAdminConsentRecordFailsClosedAndCanBeWithdrawn() {
        when(resourceService.getResource(any(ResourceDescriptor.class))).thenReturn("{not json");
        when(deploymentService.findDeployment(eq(context), eq("app"))).thenReturn(declaringApplication("skills/{current-user}/"));

        assertFalse(service.isAdminConsented("app", declaration("skills/{current-user}/")));
        assertFalse(service.describeAdminConsent(context, "app").isConsented());
        assertNull(service.withdrawAdminConsent(context, "app"));
        verify(resourceService).deleteResource(any(ResourceDescriptor.class), eq(EtagHeader.ANY));
    }

    @Test
    public void testWithdrawAdminConsent_ReturnsTheWithdrawnGrantForTheAudit() {
        when(deploymentService.findDeployment(eq(context), eq("app"))).thenReturn(declaringApplication());
        when(resourceService.getResource(any(ResourceDescriptor.class))).thenReturn("""
                {"consent": {"resources": [{"url": "skills/{current-user}/", "access": ["WRITE"]}]},
                 "grantedBy": "admin-sub", "grantedAt": 1788394665564}
                """);

        ConsentGrant withdrawn = service.withdrawAdminConsent(context, "app");

        assertEquals(List.of(resourceEntry("skills/{current-user}/")), withdrawn.getConsent().getResources());
        assertEquals("admin-sub", withdrawn.getGrantedBy());
        verify(resourceService).deleteResource(any(ResourceDescriptor.class), eq(EtagHeader.ANY));
    }

    @Test
    public void testAdminConsentRecordIsKeyedByTheResolvedApplicationsCanonicalName() {
        // keyed by the application's name, as the resolver reads it, never by the raw request id
        Application application = declaringApplication();
        application.setName("applications/public/gpt-helpe%2572");
        when(deploymentService.findDeployment(eq(context), eq("applications/public/gpt-helpe%2572")))
                .thenReturn(application);
        when(context.getInitiatorId()).thenReturn("admin-sub");
        when(clock.getAsLong()).thenReturn(1L);

        service.grantAdminConsent(context, "applications/public/gpt-helpe%2572", List.of(resourceEntry("skills/{current-user}/")));

        ArgumentCaptor<ResourceDescriptor> captor = ArgumentCaptor.forClass(ResourceDescriptor.class);
        verify(resourceService).putResource(captor.capture(), anyString(), eq(EtagHeader.ANY));
        assertEquals("gpt-helpe%72", captor.getValue().getName());
    }

    private static Application declaringApplication() {
        return declaringApplication("skills/{current-user}/");
    }

    private static Application declaringApplication(String... urls) {
        Application application = new Application();
        application.setName("app");
        application.setResourceDependencies(declaration(urls));
        return application;
    }

    private static List<ResourceDependency> declaration(String... urls) {
        return Arrays.stream(urls)
                .map(url -> new ResourceDependency()
                        .setKind(ResourceDependency.KIND)
                        .setLinkId("lnk_" + url.hashCode())
                        .setTarget(new ResourceDependency.Target().setUrl(url))
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
