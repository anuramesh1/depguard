package dev.depguard.upgrade;

import dev.depguard.agent.TriageFacts;
import dev.depguard.agent.TriageFactsService;
import dev.depguard.dependency.ParsedPom;
import dev.depguard.dependency.PomParser;
import dev.depguard.upgrade.UpgradeProposal.ProposedChange;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Human-in-the-loop upgrades: DepGuard proposes, a maintainer decides, and only then does anything change on GitHub.
 *
 * <p>Rules, enforced here (not just in the UI):
 * <ul>
 *   <li><b>Propose</b> (any DepGuard user): read the repository's pom.xml, gather the facts, edit only the vulnerable
 *       versions, store the result as PENDING. No model call: the change is deterministic and reproducible.</li>
 *   <li><b>Approve</b> (MAINTAINER, and not the person who proposed it: "four eyes"): commit exactly the stored pom.xml
 *       on a new branch and open a pull request. Refused if the pom.xml changed on GitHub since the proposal.</li>
 *   <li><b>Reject</b> (MAINTAINER): nothing happens on GitHub.</li>
 * </ul>
 * A pull request still needs a normal review and merge; DepGuard never merges.
 */
public class UpgradeService {

    private static final Logger log = LoggerFactory.getLogger(UpgradeService.class);

    private final GitHubClient gitHub;
    private final GitHubProperties github;
    private final PomParser pomParser;
    private final TriageFactsService factsService;
    private final UpgradeProposalStore store;
    private final boolean fourEyes;

    UpgradeService(GitHubClient gitHub, GitHubProperties github, PomParser pomParser, TriageFactsService factsService,
                   UpgradeProposalStore store, boolean fourEyes) {
        this.gitHub = gitHub;
        this.github = github;
        this.pomParser = pomParser;
        this.factsService = factsService;
        this.store = store;
        this.fourEyes = fourEyes;
    }

    public UpgradeProposal propose(String user) {
        GitHubClient.RepoFile pom = gitHub.readFile(github.pomPath(), github.baseBranch());
        ParsedPom parsed = pomParser.parse(pom.content());
        TriageFacts facts = factsService.gather(parsed.dependencies());

        List<PomUpgrader.Change> wanted = new ArrayList<>();
        List<ProposedChange> reasons = new ArrayList<>();
        List<String> skipped = new ArrayList<>(parsed.skipped());
        for (TriageFacts.VulnerableDependency d : facts.vulnerable()) {
            String[] gav = d.dependency().split(":");
            if (d.recommendedVersion() == null) {
                skipped.add(d.dependency() + ": no fixed version released yet");
                continue;
            }
            wanted.add(new PomUpgrader.Change(gav[0], gav[1], gav[2], d.recommendedVersion()));
            reasons.add(new ProposedChange(d.dependency(), gav[2], d.recommendedVersion(), d.majorUpgrade(), reason(d)));
        }
        PomUpgrader.Result result = PomUpgrader.apply(pom.content(), wanted);
        skipped.addAll(result.skipped());
        List<ProposedChange> applied = reasons.stream()
                .filter(r -> result.applied().stream().anyMatch(c -> r.dependency().startsWith(c.groupId() + ":" + c.artifactId() + ":")))
                .toList();

        UpgradeProposal proposal = new UpgradeProposal(UUID.randomUUID().toString(), github.repository(),
                github.baseBranch(), github.pomPath(), pom.sha(),
                applied.isEmpty() ? UpgradeProposal.NOTHING_TO_DO : UpgradeProposal.PENDING,
                user, Instant.now().toString(), null, null, applied, skipped, diff(applied), null);
        store.insert(proposal, result.pom());
        log.info("Upgrade proposal {} by {}: {} changes", proposal.id(), user, applied.size());
        return proposal;
    }

    public UpgradeProposal approve(String id, String user) {
        UpgradeProposal proposal = pending(id);
        if (fourEyes && user.equals(proposal.proposedBy())) {
            throw new UpgradeException(409, "Four-eyes rule: the person who proposed an upgrade can't approve it.");
        }
        if (!github.hasToken()) {
            throw new UpgradeException(503, "No GitHub token is configured, so DepGuard can't open pull requests.");
        }
        GitHubClient.RepoFile current = gitHub.readFile(proposal.pomPath(), proposal.baseBranch());
        if (!current.sha().equals(proposal.pomSha())) {
            throw new UpgradeException(409, "The pom.xml changed on GitHub since this proposal; propose again.");
        }
        UpgradeProposal decided = withDecision(proposal, UpgradeProposal.OPENED, user, null);
        if (!store.decide(decided)) {
            throw new UpgradeException(409, "This proposal was already decided.");
        }

        String branch = "depguard/upgrade-" + id.substring(0, 8);
        String url;
        try {
            gitHub.createBranch(branch, gitHub.branchHead(proposal.baseBranch()));
            gitHub.commitFile(proposal.pomPath(), branch, store.newPom(id).orElseThrow(), current.sha(),
                    "Upgrade " + proposal.changes().size() + " vulnerable dependencies");
            url = gitHub.openPullRequest(branch, proposal.baseBranch(),
                    "DepGuard: upgrade " + proposal.changes().size() + " vulnerable dependencies", body(proposal, user));
        } catch (RuntimeException e) {
            // Don't leave it "approved" with no pull request: back to PENDING, so it can be approved again.
            store.update(proposal);
            log.warn("GitHub call failed for proposal {}: {}", id, e.getMessage());
            throw new UpgradeException(502, "GitHub didn't accept the change; the proposal is still PENDING. "
                    + "(A half-created branch named " + branch + " may need deleting.)");
        }
        UpgradeProposal opened = withDecision(decided, UpgradeProposal.OPENED, user, url);
        store.update(opened);
        log.info("Upgrade proposal {} approved by {}: {}", id, user, url);
        return opened;
    }

    public UpgradeProposal reject(String id, String user) {
        UpgradeProposal decided = withDecision(pending(id), UpgradeProposal.REJECTED, user, null);
        if (!store.decide(decided)) {
            throw new UpgradeException(409, "This proposal was already decided.");
        }
        return decided;
    }

    public UpgradeProposal get(String id) {
        return store.find(id).orElseThrow(() -> new UpgradeException(404, "No upgrade proposal " + id));
    }

    private UpgradeProposal pending(String id) {
        UpgradeProposal proposal = get(id);
        if (!UpgradeProposal.PENDING.equals(proposal.status())) {
            throw new UpgradeException(409, "This proposal is " + proposal.status() + ", not PENDING.");
        }
        return proposal;
    }

    private static UpgradeProposal withDecision(UpgradeProposal p, String status, String user, String url) {
        return new UpgradeProposal(p.id(), p.repository(), p.baseBranch(), p.pomPath(), p.pomSha(), status,
                p.proposedBy(), p.proposedAt(), user, Instant.now().toString(), p.changes(), p.skipped(), p.diff(), url);
    }

    static String reason(TriageFacts.VulnerableDependency d) {
        List<String> parts = new ArrayList<>();
        if (d.knownExploited()) {
            parts.add("known exploited (CISA KEV)");
        }
        if (d.maxEpss() != null) {
            parts.add(String.format(Locale.ROOT, "EPSS up to %.2f%%", d.maxEpss() * 100));
        }
        parts.add(d.worstSeverity() + ", " + d.advisoryCount() + (d.advisoryCount() == 1 ? " advisory" : " advisories"));
        parts.add("CVEs: " + d.topAdvisories().stream().flatMap(a -> a.cves().stream()).map(TriageFacts.Cve::id)
                .distinct().collect(Collectors.joining(", ")));
        return String.join("; ", parts);
    }

    static String diff(List<ProposedChange> changes) {
        return changes.stream()
                .map(c -> "  " + c.dependency().substring(0, c.dependency().lastIndexOf(':')) + "\n- <version>"
                        + c.fromVersion() + "</version>\n+ <version>" + c.toVersion() + "</version>")
                .collect(Collectors.joining("\n"));
    }

    private static String body(UpgradeProposal p, String approver) {
        StringBuilder body = new StringBuilder("Upgrades dependencies with known vulnerabilities, most urgent first.\n\n")
                .append("| Dependency | From | To | Why |\n|---|---|---|---|\n");
        p.changes().forEach(c -> body.append("| `").append(c.dependency(), 0, c.dependency().lastIndexOf(':'))
                .append("` | ").append(c.fromVersion()).append(" | ").append(c.toVersion())
                .append(c.majorUpgrade() ? " ⚠️ major" : "").append(" | ").append(c.reason()).append(" |\n"));
        if (p.changes().stream().anyMatch(ProposedChange::majorUpgrade)) {
            body.append("\n⚠️ Major-version upgrades usually need code changes: run the full test suite before merging.\n");
        }
        if (!p.skipped().isEmpty()) {
            body.append("\nNot changed automatically:\n");
            p.skipped().forEach(s -> body.append("- ").append(s).append('\n'));
        }
        body.append("\nProposed by ").append(p.proposedBy()).append(", approved by ").append(approver)
                .append(" in DepGuard (proposal ").append(p.id()).append("). Data: OSV.dev, FIRST EPSS, CISA KEV.\n");
        return body.toString();
    }

    /** A rule or state problem, with the HTTP status the API should return. */
    public static class UpgradeException extends RuntimeException {
        private final int status;

        public UpgradeException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
