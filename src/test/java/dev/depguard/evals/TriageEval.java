package dev.depguard.evals;

import static org.assertj.core.api.Assertions.assertThat;

import dev.depguard.agent.TriageAgent;
import dev.depguard.agent.TriageResult;
import dev.depguard.agent.tools.VulnerabilityTools;
import dev.depguard.dependency.PomParser;
import dev.depguard.evals.GoldenSet.TriageCase;
import dev.depguard.evals.TriageOracle.Expected;
import dev.depguard.exploit.EpssClient;
import dev.depguard.exploit.KevClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the triage golden set against the real agent, live data and Bedrock, then grades every answer.
 *
 * <pre>
 * ./mvnw test -Pevals -Dtest=TriageEval                          # full: 16 cases x 2 runs (~$0.42)
 * ./mvnw test -Pevals -Dtest=TriageEval -Devals.tier=smoke       # 5 smoke cases x 1 run (~$0.06): for prompt work
 * ./mvnw test -Pevals -Dtest=TriageEval -Devals.cases=log4shell,clean-pom -Devals.repeats=3
 * ./mvnw test -Pevals -Dtest=TriageEval -Devals.judge=false      # code checks only
 * ./mvnw test -Pevals -Dtest=TriageEval -Devals.replay=target/evals/triage-scorecard.json
 *                                       # re-grade saved answers: $0 without the judge, ~$0.05 with it
 * </pre>
 *
 * <p><b>Replay</b> is for changing the graders (checks, rubric, oracle) without paying for new answers: the agent
 * isn't called, the saved answers are graded again. Copy the file first if you want to keep the old grades:
 * every run, replay included, writes target/evals/triage-scorecard.md and .json.
 *
 * <p>Fails only on gate checks (grounding and safety); quality checks are tracked in the scorecard. Each case runs
 * more than once by default because the model is not deterministic: a failure in one run out of three is a real,
 * measurable problem, and a single run can't show it.
 */
@Tag("eval")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TriageEval {

    private static final int PARALLEL = 4; // concurrent triages: fast, and well within Bedrock's rate limits
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Autowired TriageAgent agent;
    @Autowired PomParser pomParser;
    @Autowired VulnerabilityTools vulnerabilityTools;
    @Autowired EpssClient epssClient;
    @Autowired KevClient kevClient;
    @Autowired ChatModel chatModel;

    @Test
    void triageGoldenSet() throws Exception {
        String replayFile = System.getProperty("evals.replay", "");
        boolean smoke = "smoke".equals(System.getProperty("evals.tier", "full"));
        int repeats = Integer.getInteger("evals.repeats", smoke ? 1 : 2);
        boolean useJudge = Boolean.parseBoolean(System.getProperty("evals.judge", "true"));
        Set<String> only = Arrays.stream(System.getProperty("evals.cases", "").split(","))
                .filter(s -> !s.isBlank()).collect(Collectors.toSet());

        List<TriageCase> cases = GoldenSet.triageCases().stream()
                .filter(c -> only.isEmpty() || only.contains(c.id()))
                .filter(c -> !smoke || c.smoke())
                .toList();
        assertThat(cases).as("golden cases selected").isNotEmpty();

        // The oracle runs once per case: its data (OSV, EPSS, KEV) is free and doesn't change between repeats.
        TriageOracle oracle = new TriageOracle(pomParser, vulnerabilityTools, epssClient, kevClient);
        Map<String, Expected> expected = new ConcurrentHashMap<>();
        for (TriageCase c : cases) {
            expected.put(c.id(), oracle.expectedFor(c.pomXml()));
        }
        TriageJudge judge = useJudge ? new TriageJudge(chatModel) : null;

        // One task per (case, run): either a fresh agent answer, or a saved one when replaying.
        List<Callable<TriageScorecard.Run>> tasks = new ArrayList<>();
        if (replayFile.isBlank()) {
            for (TriageCase c : cases) {
                for (int repeat = 1; repeat <= repeats; repeat++) {
                    int r = repeat;
                    tasks.add(() -> grade(c, r, expected.get(c.id()), agent.triage(c.pomXml(), c.question()), judge));
                }
            }
        } else {
            Map<String, TriageCase> byId = cases.stream().collect(Collectors.toMap(TriageCase::id, c -> c));
            for (TriageScorecard.Run saved : loadRuns(Path.of(replayFile))) {
                TriageCase c = byId.get(saved.caseId());
                if (c != null) {
                    tasks.add(() -> grade(c, saved.repeat(), expected.get(c.id()), asResult(saved), judge));
                }
            }
            assertThat(tasks).as("saved runs matching the selected cases in " + replayFile).isNotEmpty();
        }

        TriageScorecard scorecard = new TriageScorecard(runAll(tasks), !replayFile.isBlank());
        String markdown = scorecard.markdown();
        Path dir = Path.of("target", "evals");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("triage-scorecard.md"), markdown);
        Files.writeString(dir.resolve("triage-scorecard.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(scorecard.runs()));
        System.out.println(markdown);

        assertThat(scorecard.gateFailures()).as("gate checks (grounding and safety) must pass on every run").isEmpty();
    }

    /** Runs the tasks in parallel (bounded), keeping their results. */
    private static List<TriageScorecard.Run> runAll(List<Callable<TriageScorecard.Run>> tasks) throws Exception {
        Semaphore slots = new Semaphore(PARALLEL);
        List<Future<TriageScorecard.Run>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Callable<TriageScorecard.Run> task : tasks) {
                futures.add(executor.submit(() -> {
                    slots.acquire();
                    try {
                        return task.call();
                    } finally {
                        slots.release();
                    }
                }));
            }
            List<TriageScorecard.Run> runs = new ArrayList<>();
            for (Future<TriageScorecard.Run> f : futures) {
                runs.add(f.get());
            }
            return runs;
        }
    }

    /** Grades one answer: the same code for live and replayed answers, so replay measures exactly what a live run would. */
    private static TriageScorecard.Run grade(TriageCase c, int repeat, Expected expected, TriageResult result,
                                             TriageJudge judge) {
        List<TriageChecks.Check> checks = TriageChecks.run(c, expected, result);
        TriageJudge.Judgement judgement = judge == null ? null : judgeSafely(judge, c, expected, result);
        return new TriageScorecard.Run(c.id(), repeat, result.durationMs(),
                result.inputTokens() == null ? 0 : result.inputTokens(),
                result.outputTokens() == null ? 0 : result.outputTokens(),
                result.toolCalls().size(), checks,
                judgement == null ? null : judgement.verdict(),
                judgement == null ? 0 : judgement.inputTokens(),
                judgement == null ? 0 : judgement.outputTokens(),
                result.answer(), result.unverifiedCves());
    }

    /** A saved run as a TriageResult, so the checks can't tell it from a live one. */
    private static TriageResult asResult(TriageScorecard.Run saved) {
        return new TriageResult(saved.answer(), List.of(),
                saved.unverifiedCves() == null ? List.of() : saved.unverifiedCves(),
                Collections.nCopies(saved.toolCalls(), "(replayed)"), List.of(), List.of(), "replayed",
                saved.inputTokens(), saved.outputTokens(), saved.durationMs());
    }

    private static List<TriageScorecard.Run> loadRuns(Path file) throws Exception {
        assertThat(file).as("replay file").exists();
        return JSON.readValue(Files.readString(file), new TypeReference<List<TriageScorecard.Run>>() { });
    }

    /** A judge that fails to answer (e.g. invalid JSON) shouldn't sink the whole run: record no verdict. */
    private static TriageJudge.Judgement judgeSafely(TriageJudge judge, TriageCase c, Expected expected, TriageResult result) {
        try {
            return judge.judge(c, expected, result);
        } catch (RuntimeException e) {
            System.err.println("Judge failed for " + c.id() + ": " + e.getMessage());
            return null;
        }
    }
}
