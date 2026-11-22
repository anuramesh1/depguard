package dev.depguard.upgrade;

import java.util.List;

/**
 * A proposed pom.xml upgrade waiting for a human decision.
 *
 * @param status     PENDING → APPROVED_PR_OPENED or REJECTED; NOTHING_TO_DO when no change was needed
 * @param pomSha     GitHub's SHA of the pom.xml the proposal was made from: approval is refused if it changed since
 * @param changes    the version changes, with the reason for each
 * @param skipped    changes DepGuard could not make itself (e.g. versions set by properties)
 * @param diff       the proposed edit, readable before approving
 * @param pullRequest the pull request URL once approved
 */
public record UpgradeProposal(String id, String repository, String baseBranch, String pomPath, String pomSha,
                              String status, String proposedBy, String proposedAt, String decidedBy, String decidedAt,
                              List<ProposedChange> changes, List<String> skipped, String diff, String pullRequest) {

    public static final String PENDING = "PENDING";
    public static final String OPENED = "APPROVED_PR_OPENED";
    public static final String REJECTED = "REJECTED";
    public static final String NOTHING_TO_DO = "NOTHING_TO_DO";

    /** One dependency change and why it is proposed. */
    public record ProposedChange(String dependency, String fromVersion, String toVersion, boolean majorUpgrade,
                                 String reason) {
    }
}
