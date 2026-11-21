package dev.depguard.evals;

import dev.depguard.evals.TriageChecks.Check;
import dev.depguard.evals.TriageJudge.Verdict;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/** Turns individual eval runs into the numbers that matter: pass rates, judge scores, latency and cost. */
final class TriageScorecard {

    // Approximate Bedrock on-demand prices, USD per million tokens (us-east-1). Check current prices.
    static final double AGENT_INPUT_PRICE = 1.00;   // Claude Haiku 4.5
    static final double AGENT_OUTPUT_PRICE = 5.00;
    static final double JUDGE_INPUT_PRICE = 0.80;   // Amazon Nova Pro
    static final double JUDGE_OUTPUT_PRICE = 3.20;

    /**
     * One agent run of one case, with its checks and (optionally) the judge's verdict. Saved to
     * triage-scorecard.json with everything a re-grade needs (answer, unverified CVEs), so it can be replayed.
     */
    record Run(String caseId, int repeat, long durationMs, int inputTokens, int outputTokens, int toolCalls,
               List<Check> checks, Verdict verdict, int judgeInputTokens, int judgeOutputTokens, String answer,
               List<String> unverifiedCves, String traceId, long cacheReadTokens, long cacheWriteTokens) {
    }

    /** Pass rate of one check across the runs where it applied. */
    record CheckRate(String name, boolean gate, int runs, int passed, double meanScore) {
        double passRate() {
            return runs == 0 ? 1 : (double) passed / runs;
        }
    }

    private final List<Run> runs;
    private final boolean replayed;

    /** @param replayed true when saved answers were re-graded: the agent's cost then wasn't spent again */
    TriageScorecard(List<Run> runs, boolean replayed) {
        this.runs = runs.stream().sorted(Comparator.comparing(Run::caseId).thenComparing(Run::repeat)).toList();
        this.replayed = replayed;
    }

    List<CheckRate> checkRates() {
        Map<String, List<Check>> byName = new LinkedHashMap<>();
        for (Run run : runs) {
            for (Check check : run.checks()) {
                // Case-specific regex checks are grouped, so the table stays readable.
                String name = check.name().startsWith("must-not-say") ? "must-not-say (safety / known failures)"
                        : check.name().startsWith("must-mention") ? "must-mention (expected content)" : check.name();
                byName.computeIfAbsent(name, k -> new ArrayList<>()).add(check);
            }
        }
        return byName.entrySet().stream().map(e -> new CheckRate(e.getKey(), e.getValue().getFirst().gate(),
                e.getValue().size(), (int) e.getValue().stream().filter(Check::passed).count(),
                e.getValue().stream().mapToDouble(Check::score).average().orElse(1))).toList();
    }

    /** Gate failures: each one is a reason to fail the build. */
    List<String> gateFailures() {
        List<String> failures = new ArrayList<>();
        for (Run run : runs) {
            run.checks().stream().filter(c -> c.gate() && !c.passed())
                    .forEach(c -> failures.add(run.caseId() + " #" + run.repeat() + ": " + c.name() + " " + c.detail()));
        }
        return failures;
    }

    String markdown() {
        StringBuilder md = new StringBuilder();
        md.append("# Triage eval scorecard\n\n");
        md.append(String.format(Locale.ROOT, "%d runs (%d cases). Agent: Claude Haiku 4.5. Judge: %s.%n%n",
                runs.size(), runs.stream().map(Run::caseId).distinct().count(), TriageJudge.JUDGE_MODEL));
        if (replayed) {
            md.append("**Replayed:** saved answers were re-graded; the agent was not called. Latency and agent cost below")
              .append(" are from the original run.\n\n");
        }

        md.append("## Code checks\n\n| Check | Type | Pass rate | Mean score |\n|---|---|---|---|\n");
        for (CheckRate rate : checkRates()) {
            md.append(String.format(Locale.ROOT, "| %s | %s | %d/%d (%.0f%%) | %.2f |%n", rate.name(),
                    rate.gate() ? "gate" : "tracked", rate.passed(), rate.runs(), rate.passRate() * 100, rate.meanScore()));
        }

        List<Verdict> verdicts = runs.stream().map(Run::verdict).filter(v -> v != null).toList();
        if (!verdicts.isEmpty()) {
            md.append("\n## LLM judge (1-5)\n\n| Dimension | Mean | Runs scoring ≤ 3 |\n|---|---|---|\n");
            judgeRow(md, "factual accuracy", verdicts, Verdict::factualAccuracy);
            judgeRow(md, "prioritization", verdicts, Verdict::prioritization);
            judgeRow(md, "upgrade notes accuracy", verdicts, Verdict::upgradeNotesAccuracy);
            judgeRow(md, "clarity", verdicts, Verdict::clarity);
        }

        long[] latencies = runs.stream().mapToLong(Run::durationMs).sorted().toArray();
        // Cached tokens are reported separately from inputTokens: reads at 0.1x, writes at 1.25x the input price.
        double agentCost = runs.stream().mapToDouble(r -> (r.inputTokens() * AGENT_INPUT_PRICE
                + r.outputTokens() * AGENT_OUTPUT_PRICE + r.cacheReadTokens() * AGENT_INPUT_PRICE * 0.1
                + r.cacheWriteTokens() * AGENT_INPUT_PRICE * 1.25) / 1e6).sum();
        double judgeCost = runs.stream().mapToDouble(r ->
                (r.judgeInputTokens() * JUDGE_INPUT_PRICE + r.judgeOutputTokens() * JUDGE_OUTPUT_PRICE) / 1e6).sum();
        md.append("\n## Latency and cost\n\n| Metric | Value |\n|---|---|\n");
        md.append(String.format(Locale.ROOT, "| Latency p50 / p95 | %.1f s / %.1f s |%n",
                percentile(latencies, 50) / 1000.0, percentile(latencies, 95) / 1000.0));
        md.append(String.format(Locale.ROOT, "| Mean tool calls | %.1f |%n",
                runs.stream().mapToInt(Run::toolCalls).average().orElse(0)));
        md.append(String.format(Locale.ROOT, "| Mean tokens in / out | %.0f / %.0f |%n",
                runs.stream().mapToInt(Run::inputTokens).average().orElse(0),
                runs.stream().mapToInt(Run::outputTokens).average().orElse(0)));
        md.append(String.format(Locale.ROOT, "| Mean cache read / write tokens | %.0f / %.0f |%n",
                runs.stream().mapToLong(Run::cacheReadTokens).average().orElse(0),
                runs.stream().mapToLong(Run::cacheWriteTokens).average().orElse(0)));
        md.append(String.format(Locale.ROOT, "| Runs answered without a model call | %d |%n",
                runs.stream().filter(r -> r.inputTokens() == 0 && r.cacheReadTokens() == 0).count()));
        md.append(String.format(Locale.ROOT, "| Agent cost per triage | $%.4f |%n", agentCost / Math.max(1, runs.size())));
        md.append(String.format(Locale.ROOT, "| Total cost of this eval run (agent + judge) | $%.2f |%n",
                (replayed ? 0 : agentCost) + judgeCost));

        md.append("\n## Failures and judge findings\n\n");
        boolean any = false;
        for (Run run : runs) {
            List<String> lines = new ArrayList<>();
            run.checks().stream().filter(c -> !c.passed())
                    .forEach(c -> lines.add((c.gate() ? "**GATE** " : "") + c.name() + ": " + c.detail()));
            if (run.verdict() != null && run.verdict().problems() != null) {
                run.verdict().problems().forEach(p -> lines.add("judge: " + p));
            }
            if (!lines.isEmpty()) {
                any = true;
                md.append("- **").append(run.caseId()).append(" #").append(run.repeat()).append("**\n");
                lines.forEach(l -> md.append("  - ").append(l.replace("\n", " ")).append('\n'));
            }
        }
        if (!any) {
            md.append("None.\n");
        }
        return md.toString();
    }

    List<Run> runs() {
        return runs;
    }

    private static void judgeRow(StringBuilder md, String name, List<Verdict> verdicts, ToDoubleFunction<Verdict> score) {
        md.append(String.format(Locale.ROOT, "| %s | %.2f | %d/%d |%n", name,
                verdicts.stream().mapToDouble(score).average().orElse(0),
                verdicts.stream().filter(v -> score.applyAsDouble(v) <= 3).count(), verdicts.size()));
    }

    private static long percentile(long[] sorted, int p) {
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.clamp(index, 0, sorted.length - 1)];
    }
}
