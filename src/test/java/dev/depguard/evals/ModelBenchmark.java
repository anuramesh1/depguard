package dev.depguard.evals;

import static org.assertj.core.api.Assertions.assertThat;

import dev.depguard.agent.TriageAgent;
import dev.depguard.agent.TriageFactsService;
import dev.depguard.agent.TriageResult;
import dev.depguard.agent.tools.KnowledgeTools;
import dev.depguard.cache.AnswerCache;
import dev.depguard.dependency.PomParser;
import dev.depguard.evals.GoldenSet.TriageCase;
import dev.depguard.evals.TriageOracle.DependencyFacts;
import dev.depguard.evals.TriageOracle.Expected;
import dev.depguard.observability.AiTracing;
import dev.depguard.observability.LangfuseProperties;
import dev.depguard.observability.ModelPricing;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Predicate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 7: the same triage golden set, graded the same way, for several Bedrock models. Quality vs latency vs cost.
 *
 * <pre>
 * ./mvnw test -Pevals -Dtest=ModelBenchmark                                  # 8 models x 16 cases x 2 (~$1.60)
 * ./mvnw test -Pevals -Dtest=ModelBenchmark -Devals.models=us.amazon.nova-lite-v1:0 -Devals.repeats=1
 * </pre>
 *
 * Only the model changes between rows: same facts (gathered in code), same prompts, same temperature and token
 * limit, same checks, same judge. Writes target/evals/model-benchmark.md, plus a full scorecard per model.
 *
 * <p><b>Judge caveat:</b> the judge is Amazon Nova Pro, so its scores of Nova models are self-graded (judges tend to
 * favour their own family). Rank on the code checks first; treat Nova judge scores as an upper bound.
 */
@Tag("eval")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "depguard.answer-cache.enabled=false")
class ModelBenchmark {

    static final List<String> DEFAULT_MODELS = List.of(
            "us.anthropic.claude-haiku-4-5-20251001-v1:0",
            "us.anthropic.claude-sonnet-4-5-20250929-v1:0",
            "us.amazon.nova-pro-v1:0",
            "us.amazon.nova-2-lite-v1:0",
            "us.amazon.nova-lite-v1:0",
            "us.amazon.nova-micro-v1:0",
            "us.meta.llama4-scout-17b-instruct-v1:0",
            "us.mistral.pixtral-large-2502-v1:0");

    @Autowired ChatModel chatModel;
    @Autowired PomParser pomParser;
    @Autowired TriageFactsService factsService;
    @Autowired KnowledgeTools knowledgeTools;
    @Autowired AiTracing tracing;
    @Autowired JsonMapper jsonMapper;
    @Autowired ModelPricing pricing;
    @Autowired LangfuseProperties langfuse;
    @Value("${spring.ai.bedrock.converse.chat.options.temperature}") double temperature;
    @Value("${spring.ai.bedrock.converse.chat.options.max-tokens}") int maxTokens;

    /** One model's results, plus the "simple" slice a router could send to a cheaper model. */
    record ModelResult(String model, TriageScorecard all, TriageScorecard simple) {
    }

    @Test
    void benchmarkModels() throws Exception {
        List<String> models = Arrays.stream(System.getProperty("evals.models", String.join(",", DEFAULT_MODELS))
                .split(",")).map(String::strip).filter(s -> !s.isBlank()).toList();
        int repeats = Integer.getInteger("evals.repeats", 2);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

        List<TriageCase> cases = GoldenSet.triageCases();
        TriageOracle oracle = new TriageOracle(pomParser, factsService);
        Map<String, Expected> expected = new LinkedHashMap<>();
        for (TriageCase c : cases) {
            expected.put(c.id(), oracle.expectedFor(c.pomXml()));
        }
        // "Simple" = something is vulnerable, but no upgrade crosses a major version: no migration guides needed.
        // These are the cases a router could send to a cheaper model.
        Predicate<String> isSimple = id -> !expected.get(id).vulnerable().isEmpty()
                && expected.get(id).vulnerable().stream().noneMatch(ModelBenchmark::majorUpgrade);
        TriageJudge judge = new TriageJudge(chatModel);
        Path dir = Path.of("target", "evals", "benchmark");
        Files.createDirectories(dir);

        List<ModelResult> results = new ArrayList<>();
        for (String model : models) { // one model at a time, so models don't compete for rate limits
            TriageAgent agent = agentFor(model);
            String session = "bench-" + stamp + "-" + model.replaceAll("[^a-z0-9]+", "-");
            List<Callable<TriageScorecard.Run>> tasks = new ArrayList<>();
            for (TriageCase c : cases) {
                for (int repeat = 1; repeat <= repeats; repeat++) {
                    int r = repeat;
                    tasks.add(() -> {
                        try {
                            TriageResult result = AiTracing.inSession(session, () -> agent.triage(c.pomXml(), c.question()));
                            return EvalRunner.grade(c, r, expected.get(c.id()), result, judge, pricing);
                        } catch (Exception e) {
                            return EvalRunner.failed(c, r, model, e);
                        }
                    });
                }
            }
            List<TriageScorecard.Run> runs = EvalRunner.runAll(tasks);
            TriageScorecard all = new TriageScorecard(runs, false);
            TriageScorecard simple = new TriageScorecard(runs.stream().filter(r -> isSimple.test(r.caseId())).toList(), false);
            results.add(new ModelResult(model, all, simple));
            Files.writeString(dir.resolve(fileName(model) + ".md"), all.markdown());
            Files.writeString(dir.resolve(fileName(model) + ".json"),
                    jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(runs));
            if (langfuse.enabled()) {
                LangfuseScores scores = new LangfuseScores(langfuse);
                runs.forEach(run -> scores.send(run, session));
            }
            System.out.println("Benchmarked " + model + ": " + summaryRow(model, all));
        }

        String report = report(results, repeats, cases.size(),
                cases.stream().map(TriageCase::id).filter(isSimple).toList());
        Files.writeString(Path.of("target", "evals", "model-benchmark.md"), report);
        System.out.println(report);
        assertThat(results).as("models benchmarked").isNotEmpty();
    }

    /** The production agent, with only the model swapped. Same prompts, temperature and token limit. */
    private TriageAgent agentFor(String model) {
        ChatClient.Builder builder = ChatClient.builder(chatModel).defaultOptions(
                BedrockChatOptions.builder().model(model).temperature(temperature).maxTokens(maxTokens));
        return new TriageAgent(builder, pomParser, factsService, knowledgeTools, tracing, jsonMapper,
                new ClassPathResource("prompts/triage-system.st"), new ClassPathResource("prompts/triage-user.st"),
                model, AnswerCache.disabled());
    }

    private static boolean majorUpgrade(DependencyFacts d) {
        return TriageFactsService.isMajorUpgrade(d.currentVersion(), d.recommendedVersion());
    }

    // ------------------------------------------------------------------ report

    private String report(List<ModelResult> results, int repeats, int caseCount, List<String> simpleCases) {
        StringBuilder md = new StringBuilder("# Model benchmark\n\n");
        md.append(String.format(Locale.ROOT, "%d models × %d cases × %d runs. Same facts, prompts, temperature %.1f, "
                + "max %d output tokens, checks and judge (%s). Prices: AWS Price List API, us-east-1.%n%n",
                results.size(), caseCount, repeats, temperature, maxTokens, TriageJudge.JUDGE_MODEL));
        md.append("**Ranked by: all gates pass → tracked checks → cost.** Judge scores marked * are self-graded "
                + "(a Nova judge grading a Nova model): read them as an upper bound.\n\n");
        md.append("| Model | Runs with all gates | Tracked checks | Top pick | Errors | Judge factual / notes / clarity "
                + "| p50 / p95 latency | Tokens in / out | Cost per triage |\n|---|---|---|---|---|---|---|---|---|\n");
        results.stream()
                .sorted((a, b) -> {
                    int byGates = Double.compare(gateRate(b.all()), gateRate(a.all()));
                    if (byGates != 0) {
                        return byGates;
                    }
                    int byTracked = Double.compare(trackedRate(b.all()), trackedRate(a.all()));
                    return byTracked != 0 ? byTracked : Double.compare(costPerTriage(a.all()), costPerTriage(b.all()));
                })
                .forEach(r -> md.append("| ").append(shortName(r.model())).append(" | ").append(summaryRow(r.model(), r.all()))
                        .append(" |\n"));
        md.append("\n## Simple poms only (no major upgrade: the slice a router could send to a cheaper model)\n\n");
        md.append("Cases: ").append(String.join(", ", simpleCases)).append("\n\n");
        md.append("| Model | Runs with all gates | Tracked checks | Top pick | Cost per triage |\n|---|---|---|---|---|\n");
        results.forEach(r -> md.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | $%.5f |%n", shortName(r.model()),
                pct(gateRate(r.simple())), pct(trackedRate(r.simple())), pct(checkRate(r.simple(), "top-pick")),
                costPerTriage(r.simple()))));
        md.append("\n## Failures per model\n\n");
        for (ModelResult r : results) {
            List<String> lines = new ArrayList<>();
            r.all().runs().forEach(run -> run.checks().stream().filter(c -> !c.passed()).forEach(c -> lines.add(
                    run.caseId() + " #" + run.repeat() + ": " + (c.gate() ? "**GATE** " : "") + c.name() + " "
                            + c.detail().replace("\n", " "))));
            md.append("### ").append(shortName(r.model())).append("\n\n");
            if (lines.isEmpty()) {
                md.append("None.\n\n");
            } else {
                lines.stream().limit(15).forEach(l -> md.append("- ").append(l, 0, Math.min(l.length(), 220)).append('\n'));
                if (lines.size() > 15) {
                    md.append("- … ").append(lines.size() - 15).append(" more\n");
                }
                md.append('\n');
            }
        }
        return md.toString();
    }

    private String summaryRow(String model, TriageScorecard s) {
        List<TriageScorecard.Run> runs = s.runs();
        long[] latencies = runs.stream().filter(r -> r.error() == null).mapToLong(TriageScorecard.Run::durationMs).sorted().toArray();
        String star = model.contains("amazon.nova") ? "*" : "";
        return String.format(Locale.ROOT, "%s | %s | %s | %d | %s / %s / %s%s | %.1f s / %.1f s | %.0f / %.0f | $%.5f",
                pct(gateRate(s)), pct(trackedRate(s)), pct(checkRate(s, "top-pick")),
                runs.stream().filter(r -> r.error() != null).count(),
                judgeMean(runs, v -> v.factualAccuracy()), judgeMean(runs, v -> v.upgradeNotesAccuracy()),
                judgeMean(runs, v -> v.clarity()), star,
                percentile(latencies, 50) / 1000.0, percentile(latencies, 95) / 1000.0,
                runs.stream().mapToInt(TriageScorecard.Run::inputTokens).average().orElse(0),
                runs.stream().mapToInt(TriageScorecard.Run::outputTokens).average().orElse(0),
                costPerTriage(s));
    }

    static double gateRate(TriageScorecard s) {
        return s.runs().isEmpty() ? 0 : s.runs().stream().filter(TriageScorecard.Run::gatesPassed).count() / (double) s.runs().size();
    }

    /** Mean pass rate over every tracked (non-gate) check in every run. */
    static double trackedRate(TriageScorecard s) {
        return s.runs().stream().flatMap(r -> r.checks().stream()).filter(c -> !c.gate())
                .mapToDouble(c -> c.passed() ? 1 : 0).average().orElse(0);
    }

    static double checkRate(TriageScorecard s, String name) {
        return s.runs().stream().flatMap(r -> r.checks().stream()).filter(c -> c.name().equals(name))
                .mapToDouble(c -> c.passed() ? 1 : 0).average().orElse(Double.NaN);
    }

    static double costPerTriage(TriageScorecard s) {
        return s.runs().stream().mapToDouble(TriageScorecard.Run::agentCostUsd).average().orElse(0);
    }

    private static String judgeMean(List<TriageScorecard.Run> runs,
                                    java.util.function.ToDoubleFunction<TriageJudge.Verdict> score) {
        var values = runs.stream().filter(r -> r.verdict() != null).map(TriageScorecard.Run::verdict).mapToDouble(score);
        var stats = values.summaryStatistics();
        return stats.getCount() == 0 ? "–" : String.format(Locale.ROOT, "%.2f", stats.getAverage());
    }

    private static String pct(double rate) {
        return Double.isNaN(rate) ? "–" : String.format(Locale.ROOT, "%.0f%%", rate * 100);
    }

    private static long percentile(long[] sorted, int p) {
        if (sorted.length == 0) {
            return 0;
        }
        return sorted[Math.clamp((int) Math.ceil(p / 100.0 * sorted.length) - 1, 0, sorted.length - 1)];
    }

    private static String shortName(String model) {
        return model.replaceFirst("^(us|global)\\.", "").replaceFirst("-v\\d+:\\d+$", "");
    }

    private static String fileName(String model) {
        return model.replaceAll("[^A-Za-z0-9.-]+", "_");
    }
}
