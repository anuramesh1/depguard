package dev.depguard.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;

/**
 * What happened during one triage request: which tools ran, which CVE IDs they returned, and which
 * knowledge-base sources the agent read.
 *
 * <p>The tools are shared singletons, so per-request state can't live in them. Instead the agent
 * passes one TriageSession per request through Spring AI's ToolContext, and each tool records into it.
 * The citation check later compares the answer against {@link #knownCves()}.
 */
public final class TriageSession {

    static final String KEY = "triageSession";
    public static final int MAX_TOOL_CALLS = 8;
    public static final String BUDGET_EXHAUSTED = "Tool call limit reached. Answer now with the results you already have.";
    private static final Logger log = LoggerFactory.getLogger(TriageSession.class);

    private final Set<String> knownCves = ConcurrentHashMap.newKeySet();
    private final List<String> toolCalls = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> sources = Collections.synchronizedSet(new LinkedHashSet<>());

    /** The session for this request, or a throwaway one if a tool is called outside the agent (e.g. in tests). */
    public static TriageSession from(ToolContext context) {
        Object session = context == null ? null : context.getContext().get(KEY);
        return session instanceof TriageSession triageSession ? triageSession : new TriageSession();
    }

    /**
     * Records a tool call and says whether it may run. A normal triage needs 3 to 5 calls; the cap stops a
     * confused model from looping (each round trip costs tokens and time).
     */
    public boolean recordToolCall(String description) {
        log.info("Agent tool call: {}", description);
        synchronized (toolCalls) {
            toolCalls.add(description);
            return toolCalls.size() <= MAX_TOOL_CALLS;
        }
    }

    public void recordCves(Collection<String> cveIds) {
        knownCves.addAll(cveIds);
    }

    /** A knowledge-base document the agent read ("title (url)"), in the order first seen. */
    public void recordSource(String source) {
        sources.add(source);
    }

    public List<String> sources() {
        synchronized (sources) {
            return List.copyOf(sources);
        }
    }

    public Set<String> knownCves() {
        return Set.copyOf(knownCves);
    }

    public List<String> toolCalls() {
        synchronized (toolCalls) {
            return List.copyOf(toolCalls);
        }
    }
}
