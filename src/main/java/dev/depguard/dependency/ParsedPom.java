package dev.depguard.dependency;

import java.util.List;

/**
 * Result of reading a pom.xml.
 *
 * @param dependencies dependencies we can check (version is known)
 * @param skipped      dependencies we could not check, with the reason; shown to the user so a
 *                     "no vulnerabilities" answer is never silently incomplete
 */
public record ParsedPom(List<Dependency> dependencies, List<String> skipped) {
}
