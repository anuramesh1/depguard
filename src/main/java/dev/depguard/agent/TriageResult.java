package dev.depguard.agent;

import java.util.List;

/**
 * The response of POST /api/triage.
 *
 * @param answer         the agent's Markdown answer
 * @param citedCves      CVE IDs the answer mentions
 * @param unverifiedCves CVE IDs in the answer that no tool returned: treat these with suspicion
 * @param toolCalls      which tools the agent called, in order (a simple trace)
 * @param sources        knowledge-base documents the agent read (RAG), as "title (url)"
 * @param skipped        dependencies that could not be checked (version unknown)
 * @param model          the Bedrock model that answered
 * @param inputTokens    tokens sent to the model, across all tool-calling rounds (drives cost)
 * @param outputTokens   tokens the model generated
 */
public record TriageResult(
        String answer,
        List<String> citedCves,
        List<String> unverifiedCves,
        List<String> toolCalls,
        List<String> sources,
        List<String> skipped,
        String model,
        Integer inputTokens,
        Integer outputTokens,
        long durationMs) {
}
