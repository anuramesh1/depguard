package dev.depguard.upgrade;

import dev.depguard.cache.SqlRunner;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Upgrade proposals in Postgres (local Docker or Aurora): they must survive restarts and be visible to whichever
 * Lambda instance handles the approval. The new pom.xml text is stored so that what gets committed is exactly what
 * the approver saw.
 */
class UpgradeProposalStore {

    static final List<String> SCHEMA = List.of("""
            CREATE TABLE IF NOT EXISTS upgrade_proposal (
                id          text PRIMARY KEY,
                status      text NOT NULL,
                proposal    text NOT NULL,          -- the UpgradeProposal as JSON
                new_pom     text NOT NULL,
                created_at  timestamptz NOT NULL DEFAULT now(),
                updated_at  timestamptz NOT NULL DEFAULT now()
            )""");

    private final SqlRunner sql;
    private final JsonMapper json;
    private volatile boolean schemaReady;

    UpgradeProposalStore(SqlRunner sql, JsonMapper json) {
        this.sql = sql;
        this.json = json;
    }

    void insert(UpgradeProposal proposal, String newPom) {
        ensureSchema();
        sql.update("INSERT INTO upgrade_proposal (id, status, proposal, new_pom) VALUES (:id, :status, :proposal, :pom)",
                Map.of("id", proposal.id(), "status", proposal.status(), "proposal", json.writeValueAsString(proposal),
                        "pom", newPom));
    }

    /**
     * Moves a proposal on only if it is still PENDING: two approvers clicking at once can't both open a PR.
     *
     * @return true when this call made the transition
     */
    boolean decide(UpgradeProposal decided) {
        ensureSchema();
        List<Map<String, Object>> updated = sql.query("""
                UPDATE upgrade_proposal SET status = :status, proposal = :proposal, updated_at = now()
                WHERE id = :id AND status = 'PENDING' RETURNING id""",
                Map.of("id", decided.id(), "status", decided.status(), "proposal", json.writeValueAsString(decided)));
        return !updated.isEmpty();
    }

    /** Records the pull request URL after a successful approval. */
    void update(UpgradeProposal proposal) {
        ensureSchema();
        sql.update("UPDATE upgrade_proposal SET status = :status, proposal = :proposal, updated_at = now() WHERE id = :id",
                Map.of("id", proposal.id(), "status", proposal.status(), "proposal", json.writeValueAsString(proposal)));
    }

    Optional<UpgradeProposal> find(String id) {
        return row(id).map(r -> json.readValue((String) r.get("proposal"), UpgradeProposal.class));
    }

    Optional<String> newPom(String id) {
        return row(id).map(r -> (String) r.get("new_pom"));
    }

    private Optional<Map<String, Object>> row(String id) {
        ensureSchema();
        return sql.query("SELECT proposal, new_pom FROM upgrade_proposal WHERE id = :id", Map.of("id", id))
                .stream().findFirst();
    }

    private void ensureSchema() {
        if (!schemaReady) {
            synchronized (this) {
                if (!schemaReady) {
                    SCHEMA.forEach(statement -> sql.update(statement, Map.of()));
                    schemaReady = true;
                }
            }
        }
    }
}
