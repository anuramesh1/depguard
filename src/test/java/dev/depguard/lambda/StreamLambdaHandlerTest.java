package dev.depguard.lambda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.amazonaws.services.lambda.runtime.Context;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Feeds the Lambda handler the same JSON event API Gateway sends, without deploying anything.
 * Proves the Spring app boots and routes correctly inside the Lambda adapter. No network calls.
 */
class StreamLambdaHandlerTest {

    private final StreamLambdaHandler handler = new StreamLambdaHandler();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void healthCheckReturnsUp() throws Exception {
        JsonNode response = invoke(event("GET", "/actuator/health", null, null));

        assertThat(response.get("statusCode").asInt()).isEqualTo(200);
        assertThat(response.get("body").asText()).contains("\"status\":\"UP\"");
    }

    @Test
    void invalidPomReturnsProblemDetail() throws Exception {
        JsonNode response = invoke(event("POST", "/api/scans", "application/xml", "<html/>"));

        assertThat(response.get("statusCode").asInt()).isEqualTo(400);
        assertThat(response.get("body").asText()).contains("Root element must be <project>");
    }

    private JsonNode invoke(String event) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        handler.handleRequest(new ByteArrayInputStream(event.getBytes(StandardCharsets.UTF_8)), output, mock(Context.class));
        return json.readTree(output.toByteArray());
    }

    /** A minimal API Gateway HTTP API (payload format 2.0) event. */
    private String event(String method, String path, String contentType, String body) throws Exception {
        var root = json.createObjectNode();
        root.put("version", "2.0");
        root.put("routeKey", "$default");
        root.put("rawPath", path);
        root.put("rawQueryString", "");
        var headers = root.putObject("headers");
        headers.put("host", "test.execute-api.us-east-1.amazonaws.com");
        if (contentType != null) {
            headers.put("content-type", contentType);
        }
        var requestContext = root.putObject("requestContext");
        requestContext.put("stage", "$default");
        var http = requestContext.putObject("http");
        http.put("method", method);
        http.put("path", path);
        http.put("protocol", "HTTP/1.1");
        http.put("sourceIp", "203.0.113.10");
        if (body != null) {
            root.put("body", body);
        }
        root.put("isBase64Encoded", false);
        return json.writeValueAsString(root);
    }
}
