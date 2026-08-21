package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.consent.Consent;
import com.epam.aidial.core.server.data.consent.ReviewConsentResponse;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
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

import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @SneakyThrows
    private static void verifyJson(String expected, Object actual) {
        String json = MAPPER.writeValueAsString(actual);
        assertEquals(expected, MAPPER.readTree(json).toPrettyString());
    }

    private static Config buildConfig(String payload) {
        return ProxyUtil.convertToObject(payload, Config.class);
    }

}
