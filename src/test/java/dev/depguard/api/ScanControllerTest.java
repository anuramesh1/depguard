package dev.depguard.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.depguard.dependency.PomParseException;
import dev.depguard.osv.OsvUnavailableException;
import dev.depguard.scan.ScanReport;
import dev.depguard.scan.ScanService;
import java.time.Instant;
import java.util.List;
import dev.depguard.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Web-layer test: HTTP status codes and JSON shape, with the service mocked out. */
// Authentication on, as when deployed: scans need a login too, so only users you created can run anything.
@WebMvcTest(controllers = ScanController.class, properties = {
        "depguard.auth.issuer-uri=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_test",
        "depguard.auth.client-id=test-client"})
@Import(SecurityConfig.class)
class ScanControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ScanService scanService;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor viewer() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));
    }

    @Test
    void scansNeedALogin() throws Exception {
        mvc.perform(post("/api/scans").contentType(MediaType.APPLICATION_XML).content("<project/>"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(scanService);
    }

    @Test
    void returnsReport() throws Exception {
        when(scanService.scan(anyString())).thenReturn(new ScanReport(3, 0, List.of(), List.of(), Instant.EPOCH));

        mvc.perform(post("/api/scans").with(viewer()).contentType(MediaType.APPLICATION_XML).content("<project/>"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dependenciesScanned").value(3));
    }

    @Test
    void invalidPomIs400ProblemDetail() throws Exception {
        when(scanService.scan(anyString())).thenThrow(new PomParseException("Root element must be <project>"));

        mvc.perform(post("/api/scans").with(viewer()).contentType(MediaType.APPLICATION_XML).content("<html/>"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid pom.xml"))
                .andExpect(jsonPath("$.detail").value("Root element must be <project>"));
    }

    @Test
    void osvOutageIs503WithoutLeakingDetails() throws Exception {
        when(scanService.scan(anyString())).thenThrow(new OsvUnavailableException("timeout calling https://internal"));

        mvc.perform(post("/api/scans").with(viewer()).contentType(MediaType.APPLICATION_XML).content("<project/>"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("The vulnerability database is unavailable. Try again shortly."));
    }

    @Test
    void rejectsOversizedPom() throws Exception {
        String huge = "x".repeat(ScanController.MAX_POM_CHARS + 1);

        mvc.perform(post("/api/scans").with(viewer()).contentType(MediaType.TEXT_PLAIN).content(huge))
                .andExpect(status().isPayloadTooLarge());
    }
}
