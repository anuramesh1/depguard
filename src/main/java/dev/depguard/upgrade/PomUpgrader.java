package dev.depguard.upgrade;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Changes dependency versions in a pom.xml by editing only the {@code <version>} text, so the file's formatting,
 * comments and ordering stay exactly as the team wrote them (re-serialising the XML would rewrite the whole file and
 * make the pull request unreadable).
 *
 * <p>A version set through a property ({@code ${jackson.version}}) is reported, not changed: the property may be shared
 * by other artifacts, so that decision belongs to a person.
 */
final class PomUpgrader {

    private static final Pattern DEPENDENCY = Pattern.compile("<dependency>.*?</dependency>", Pattern.DOTALL);

    private PomUpgrader() {
    }

    /** One requested change: groupId:artifactId from one version to another. */
    record Change(String groupId, String artifactId, String fromVersion, String toVersion) {
    }

    /**
     * @param pom     the edited pom.xml text
     * @param applied changes that were made
     * @param skipped changes that couldn't be made, with the reason
     */
    record Result(String pom, List<Change> applied, List<String> skipped) {
    }

    static Result apply(String pom, List<Change> changes) {
        String result = pom;
        List<Change> applied = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Change change : changes) {
            Matcher dependencies = DEPENDENCY.matcher(result);
            boolean done = false;
            while (dependencies.find() && !done) {
                String block = dependencies.group();
                if (!tag(block, "groupId", change.groupId()) || !tag(block, "artifactId", change.artifactId())) {
                    continue;
                }
                Matcher version = Pattern.compile("<version>\\s*([^<]*?)\\s*</version>").matcher(block);
                if (!version.find()) {
                    skipped.add(change.groupId() + ":" + change.artifactId() + " has no <version> here (managed by a parent or BOM)");
                } else if (version.group(1).startsWith("${")) {
                    skipped.add(change.groupId() + ":" + change.artifactId() + " uses " + version.group(1)
                            + ": update that property by hand (it may be shared)");
                } else if (!version.group(1).equals(change.fromVersion())) {
                    skipped.add(change.groupId() + ":" + change.artifactId() + " is at " + version.group(1)
                            + ", not " + change.fromVersion() + " (the pom changed?)");
                } else {
                    String newBlock = block.substring(0, version.start(1)) + change.toVersion() + block.substring(version.end(1));
                    result = result.substring(0, dependencies.start()) + newBlock + result.substring(dependencies.end());
                    applied.add(change);
                }
                done = true;
            }
            if (!done) {
                skipped.add(change.groupId() + ":" + change.artifactId() + " not found in the pom");
            }
        }
        return new Result(result, applied, skipped);
    }

    private static boolean tag(String block, String name, String value) {
        return Pattern.compile("<" + name + ">\\s*" + Pattern.quote(value) + "\\s*</" + name + ">").matcher(block).find();
    }
}
