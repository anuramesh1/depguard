package dev.depguard.api;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.depguard.rag.HybridRetriever;
import dev.depguard.rag.SearchHit;
import java.util.List;
import dev.depguard.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The knowledge search endpoint with authentication on, as it is when deployed. */
@WebMvcTest(controllers = KnowledgeController.class, properties = {
        "depguard.auth.issuer-uri=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_test",
        "depguard.auth.client-id=test-client"})
@Import(SecurityConfig.class)
class KnowledgeControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private HybridRetriever retriever;

    @Test
    void needsAToken() throws Exception {
        mvc.perform(get("/api/knowledge/search").param("q", "log4j")).andExpect(status().isUnauthorized());
    }

    @Test
    void showsBothRanksAndTheFusedScore() throws Exception {
        when(retriever.search("Text4Shell", 5)).thenReturn(List.of(new SearchHit(
                "GHSA-599f-7c49-w659", "osv", "GHSA-599f-7c49-w659: Arbitrary code execution in Apache Commons Text",
                "https://osv.dev/vulnerability/GHSA-599f-7c49-w659", "Advisory text", List.of("CVE-2022-42889"),
                3L, 1L, 1.0 / 63 + 1.0 / 61)));

        mvc.perform(get("/api/knowledge/search").param("q", "Text4Shell").with(TriageControllerTest.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].vectorRank").value(3))
                .andExpect(jsonPath("$[0].keywordRank").value(1))
                .andExpect(jsonPath("$[0].score").value(0.0323))
                .andExpect(jsonPath("$[0].source").value("osv"));
    }

    @Test
    void aDatabaseOutageIsA503WithoutInternalDetails() throws Exception {
        when(retriever.search(anyString(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("Connection to localhost:5432 refused"));

        mvc.perform(get("/api/knowledge/search").param("q", "log4j").with(TriageControllerTest.viewer()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("The knowledge base is unavailable right now. Try again shortly."));
    }

    @Test
    void rejectsAnEmptyQuery() throws Exception {
        mvc.perform(get("/api/knowledge/search").param("q", " ").with(TriageControllerTest.viewer()))
                .andExpect(status().isBadRequest());
    }
}
