package dev.depguard.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.depguard.agent.TriageAgent;
import dev.depguard.agent.TriageResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.core.exception.SdkClientException;

/** The triage endpoint with an API key configured, as it is when deployed. */
@WebMvcTest(controllers = TriageController.class, properties = "depguard.api-key=test-key")
class TriageControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private TriageAgent triageAgent;

    @Test
    void rejectsMissingOrWrongApiKey() throws Exception {
        mvc.perform(post("/api/triage").contentType(MediaType.APPLICATION_XML).content("<project/>"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/triage").header("X-Api-Key", "wrong").contentType(MediaType.APPLICATION_XML).content("<project/>"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsTheAgentsAnswerWithTheRightKey() throws Exception {
        when(triageAgent.triage(anyString(), any())).thenReturn(new TriageResult(
                "## Upgrade first", List.of(), List.of(), List.of("findVulnerabilities(1 dependencies)"),
                List.of(), List.of(), "fake-model", 100, 20, 5));

        mvc.perform(post("/api/triage").header("X-Api-Key", "test-key")
                        .contentType(MediaType.APPLICATION_XML).content("<project/>"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("## Upgrade first"))
                .andExpect(jsonPath("$.toolCalls[0]").value("findVulnerabilities(1 dependencies)"));
    }

    @Test
    void rejectsOverlongQuestions() throws Exception {
        mvc.perform(post("/api/triage").header("X-Api-Key", "test-key")
                        .param("question", "x".repeat(TriageController.MAX_QUESTION_CHARS + 1))
                        .contentType(MediaType.APPLICATION_XML).content("<project/>"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void bedrockFailureIs503WithoutDetails() throws Exception {
        when(triageAgent.triage(anyString(), any())).thenThrow(SdkClientException.create("Unable to load credentials"));

        mvc.perform(post("/api/triage").header("X-Api-Key", "test-key")
                        .contentType(MediaType.APPLICATION_XML).content("<project/>"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("The AI model is unavailable right now. Try again shortly."));
    }
}
