package dev.depguard.api;

import dev.depguard.upgrade.UpgradeProposal;
import dev.depguard.upgrade.UpgradeService;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Human-approved upgrade pull requests (see {@link UpgradeService} for the rules).
 *
 * <pre>
 * POST /api/upgrades                 propose an upgrade of the configured repository (VIEWER or MAINTAINER)
 * GET  /api/upgrades/{id}            the proposal: changes, reasons, diff, status
 * POST /api/upgrades/{id}/approve    open the pull request (MAINTAINER, not the proposer)
 * POST /api/upgrades/{id}/reject     close the proposal (MAINTAINER)
 * </pre>
 */
@RestController
@RequestMapping("/api/upgrades")
public class UpgradeController {

    /** Name used when authentication is off (local development). */
    static final String LOCAL_USER = "local-developer";

    private final UpgradeService upgrades;

    public UpgradeController(UpgradeService upgrades) {
        this.upgrades = upgrades;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UpgradeProposal propose(Principal user) {
        return upgrades.propose(name(user));
    }

    @GetMapping("/{id}")
    public UpgradeProposal get(@PathVariable String id) {
        return upgrades.get(id);
    }

    @PostMapping("/{id}/approve")
    public UpgradeProposal approve(@PathVariable String id, Principal user) {
        return upgrades.approve(id, name(user));
    }

    @PostMapping("/{id}/reject")
    public UpgradeProposal reject(@PathVariable String id, Principal user) {
        return upgrades.reject(id, name(user));
    }

    private static String name(Principal user) {
        return user == null ? LOCAL_USER : user.getName();
    }
}
