package com.epam.aidial.core.controller;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Assistant;
import com.epam.aidial.core.config.Assistants;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.data.AssistantData;
import com.epam.aidial.core.data.ListData;
import com.epam.aidial.core.util.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static com.epam.aidial.core.config.Config.ASSISTANT;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class AssistantControllerTest {

    @Mock
    private ProxyContext context;

    private Config config;

    @InjectMocks
    private AssistantController controller;

    @BeforeEach
    public void beforeEach() {
        config = new Config();
        Assistants assistants = new Assistants();
        Assistant baseAssistant = new Assistant();
        baseAssistant.setName(ASSISTANT);
        assistants.getAssistants().put(ASSISTANT, baseAssistant);
        config.setAssistant(assistants);
        when(context.getConfig()).thenReturn(config);
    }

    @Test
    public void testGetAssistant_Success() {
        Assistant assistant = new Assistant();
        assistant.setName("key");
        config.getAssistant().getAssistants().put("key", assistant);

        controller.getAssistant("key");

        verify(context).respond(eq(HttpStatus.OK), argThat((ArgumentMatcher<Object>) argument -> {
            if (!(argument instanceof AssistantData data)) {
                return false;
            }
            return "key".equals(data.getId());
        }));
    }

    @Test
    public void testGetAssistant_NotFound() {
        controller.getAssistant("key");

        verify(context).respond(eq(HttpStatus.NOT_FOUND));
    }

    @Test
    public void testGetAssistant_NotFoundBaseAssistant() {

        controller.getAssistant(ASSISTANT);

        verify(context).respond(eq(HttpStatus.NOT_FOUND));
    }

    @Test
    public void testGetAssistants_Success() {
        Assistant assistant = new Assistant();
        assistant.setName("key");
        config.getAssistant().getAssistants().put("key", assistant);

        controller.getAssistants();

        verify(context).respond(eq(HttpStatus.OK), argThat((ArgumentMatcher<Object>) argument -> {
            if (!(argument instanceof ListData list)) {
                return false;
            }
            List<AssistantData> data = list.getData();
            if (data.size() != 1) {
                return false;
            }
            return "key".equals(data.get(0).getId());
        }));
    }

}
