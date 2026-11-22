package dev.depguard.evals;

import dev.depguard.agent.TriageResult;
import dev.depguard.evals.GoldenSet.TriageCase;
import dev.depguard.evals.TriageOracle.Expected;
import dev.depguard.observability.ModelPricing;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * What TriageEval and ModelBenchmark share: running triages in parallel and grading each answer the same way.
 * One grading path means a benchmark score and an eval score mean exactly the same thing.
 */
final class EvalRunner {

    /** Concurrent triages: fast, and well within Bedrock's rate limits. */
    static final int PARALLEL = 4;

    private EvalRunner() {
    }

    /** Runs the tasks in parallel (bounded), keeping their results in order. */
    static List<TriageScorecard.Run> runAll(List<Callable<TriageScorecard.Run>> tasks) throws Exception {
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
    static TriageScorecard.Run grade(TriageCase c, int repeat, Expected expected, TriageResult result, TriageJudge judge,
                                     ModelPricing pricing) {
        List<TriageChecks.Check> checks = TriageChecks.run(c, expected, result);
        TriageJudge.Judgement judgement = judge == null ? null : judgeSafely(judge, c, expected, result);
        int input = result.inputTokens() == null ? 0 : result.inputTokens();
        int output = result.outputTokens() == null ? 0 : result.outputTokens();
        long cacheRead = result.cacheReadInputTokens() == null ? 0 : result.cacheReadInputTokens();
        long cacheWrite = result.cacheWriteInputTokens() == null ? 0 : result.cacheWriteInputTokens();
        double agentCost = pricing.costOf(result.model(), input, output, cacheRead, cacheWrite).orElse(0.0);
        double judgeCost = judgement == null ? 0 : pricing.costOf(TriageJudge.JUDGE_MODEL,
                judgement.inputTokens(), judgement.outputTokens()).orElse(0.0);
        return new TriageScorecard.Run(c.id(), repeat, result.model(), result.durationMs(), input, output,
                result.toolCalls().size(), checks,
                judgement == null ? null : judgement.verdict(),
                judgement == null ? 0 : judgement.inputTokens(),
                judgement == null ? 0 : judgement.outputTokens(),
                result.answer(), result.unverifiedCves(), result.traceId(), cacheRead, cacheWrite, agentCost, judgeCost,
                null);
    }

    /**
     * A triage that threw (model error, throttling, unsupported feature): recorded as a failed run, not a crashed
     * benchmark. The failing "completed-without-error" gate makes it count against the model.
     */
    static TriageScorecard.Run failed(TriageCase c, int repeat, String model, Exception error) {
        String message = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        return new TriageScorecard.Run(c.id(), repeat, model, 0, 0, 0, 0,
                List.of(TriageChecks.Check.of("completed-without-error", true, false,
                        message.substring(0, Math.min(200, message.length())))),
                null, 0, 0, "", List.of(), null, 0, 0, 0, 0, message);
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
