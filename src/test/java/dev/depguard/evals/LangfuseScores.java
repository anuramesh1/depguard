package dev.depguard.evals;

import dev.depguard.evals.TriageChecks.Check;
import dev.depguard.observability.LangfuseProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Attaches eval results to the triage traces in Langfuse, as scores.
 *
 * <p>This is what gives evals a history: each eval run is a Langfuse session (all its traces grouped), and each
 * trace carries its scores (judge dimensions, gate pass, top pick). In Langfuse you can then open a low-scoring
 * answer and see exactly which tool results and prompts produced it, or chart a score across runs.
 *
 * <p>Uses Langfuse's public REST API (POST /api/public/scores). Failures are reported, never fatal: the eval's
 * own scorecard is the source of truth.
 */
final class LangfuseScores {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final URI endpoint;
    private final String authorization;

    LangfuseScores(LangfuseProperties langfuse) {
        this.endpoint = URI.create(langfuse.host().replaceAll("/+$", "") + "/api/public/scores");
        String credentials = langfuse.publicKey() + ":" + langfuse.secretKey();
        this.authorization = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /** Sends one run's scores. Returns how many were accepted. */
    int send(TriageScorecard.Run run, String evalRunId) {
        if (run.traceId() == null) {
            return 0;
        }
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("eval.gates_passed", run.checks().stream().filter(Check::gate).allMatch(Check::passed) ? 1.0 : 0.0);
        run.checks().stream().filter(c -> c.name().equals("top-pick")).findFirst()
                .ifPresent(c -> scores.put("eval.top_pick", c.passed() ? 1.0 : 0.0));
        long tracked = run.checks().stream().filter(c -> !c.gate()).count();
        if (tracked > 0) {
            scores.put("eval.tracked_pass_rate",
                    run.checks().stream().filter(c -> !c.gate() && c.passed()).count() / (double) tracked);
        }
        if (run.verdict() != null) {
            scores.put("judge.factual_accuracy", (double) run.verdict().factualAccuracy());
            scores.put("judge.prioritization", (double) run.verdict().prioritization());
            scores.put("judge.upgrade_notes_accuracy", (double) run.verdict().upgradeNotesAccuracy());
            scores.put("judge.clarity", (double) run.verdict().clarity());
        }
        String comment = "eval run " + evalRunId + ", case " + run.caseId() + " #" + run.repeat();
        int accepted = 0;
        for (Map.Entry<String, Double> score : scores.entrySet()) {
            accepted += post(Map.of("traceId", run.traceId(), "name", score.getKey(), "value", score.getValue(),
                    "dataType", "NUMERIC", "comment", comment)) ? 1 : 0;
        }
        return accepted;
    }

    private boolean post(Map<String, Object> body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", authorization)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                System.err.println("Langfuse rejected score " + body.get("name") + ": HTTP " + response.statusCode());
                return false;
            }
            return true;
        } catch (Exception e) {
            System.err.println("Could not send score to Langfuse: " + e.getMessage());
            return false;
        }
    }
}
