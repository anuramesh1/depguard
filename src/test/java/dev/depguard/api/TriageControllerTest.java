package dev.depguard.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.depguard.agent.TriageAgent;
import dev.depguard.agent.TriageResult;
import java.util.List;
import dev.depguard.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.core.exception.SdkClientException;

/** The triage endpoint with authentication on, as it is when deployed (Cognito tokens, mocked here). */
@WebMvcTest(controllers = TriageController.class, properties = {
        "depguard.auth.issuer-uri=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_test",
        "depguard.auth.client-id=test-client"})
@Import(SecurityConfig.class)
class TriageControllerTest {

    static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor viewer() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private TriageAgent triageAgent;

    @Test
    void needsATokenWithARole() throws Exception {
        mvc.perform(post("/api/triage").contentType(MediaType.APPLICATION_XML).content("<project/>"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/triage").with(jwt()).contentType(MediaType.APPLICATION_XML).content("<project/>"))
                .andExpect(status().isForbidden()); // a valid token, but in no DepGuard group
    }

    @Test
    void aViewerGetsTheAgentsAnswer() throws Exception {
        when(triageAgent.triage(anyString(), any())).thenReturn(new TriageResult(
                "## Upgrade first", List.of(), List.of(), List.of("findVulnerabilities(1 dependencies)"),
                List.of(), List.of(), "fake-model", 100, 20, 0L, 0L, 5, "trace-1", null));

        mvc.perform(post("/api/triage").with(viewer())
                        .contentType(MediaType.APPLICATION_XML).content("<project/>"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("## Upgrade first"))
                .andExpect(jsonPath("$.toolCalls[0]").value("findVulnerabilities(1 dependencies)"));
    }

    @Test
    void rejectsOverlongQuestions() throws Exception {
        mvc.perform(post("/api/triage").with(viewer())
                        .param("question", "x".repeat(TriageController.MAX_QUESTION_CHARS + 1))
                        .contentType(MediaType.APPLICATION_XML).content("<project/>"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void bedrockFailureIs503WithoutDetails() throws Exception {
        when(triageAgent.triage(anyString(), any())).thenThrow(SdkClientException.create("Unable to load credentials"));

        mvc.perform(post("/api/triage").with(viewer())
                        .contentType(MediaType.APPLICATION_XML).content("<project/>"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("The AI model is unavailable right now. Try again shortly."));
    }
}
