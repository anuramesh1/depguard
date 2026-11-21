package dev.depguard.evals;

import static org.assertj.core.api.Assertions.assertThat;

import dev.depguard.agent.TriageResult;
import dev.depguard.evals.GoldenSet.TriageCase;
import dev.depguard.evals.TriageChecks.Check;
import dev.depguard.evals.TriageOracle.DependencyFacts;
import dev.depguard.evals.TriageOracle.Expected;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests the graders themselves, with hand-written answers. A buggy check produces a confident but wrong
 * scorecard, which is worse than no eval at all. These run in the normal (free) test suite.
 */
class TriageChecksTest {

    private static final DependencyFacts LOG4J = new DependencyFacts("log4j-core",
            "org.apache.logging.log4j:log4j-core:2.14.1", "compile", "2.25.4", 8, true, true, 0.94,
            List.of(new TriageOracle.CveFact("CVE-2021-44228", "CRITICAL", "2.15.0", 0.94, true)));
    private static final DependencyFacts JACKSON = new DependencyFacts("jackson-databind",
            "com.fasterxml.jackson.core:jackson-databind:2.9.8", "compile", "2.18.11", 60, false, false, 0.27,
            List.of(new TriageOracle.CveFact("CVE-2020-8840", "CRITICAL", "2.9.10.3", 0.27, false)));
    private static final DependencyFacts JUNIT = new DependencyFacts("junit", "junit:junit:4.12", "test", "4.13.1",
            1, false, false, 0.02,
            List.of(new TriageOracle.CveFact("CVE-2020-15250", "MODERATE", "4.13.1", 0.02, false)));
    private static final Expected EXPECTED = new Expected(List.of(LOG4J, JACKSON, JUNIT), Set.of("slf4j-api"), List.of());
    private static final TriageCase CASE = new TriageCase("test", "", List.of(), null,
            List.of("CVE-2021-44228"), List.of("PWNED"), null, null);

    private static TriageResult result(String answer, List<String> unverified) {
        return new TriageResult(answer, List.of(), unverified, List.of(), List.of(), List.of(), "fake", 0, 0, 0);
    }

    private static Check check(List<Check> checks, String name) {
        return checks.stream().filter(c -> c.name().startsWith(name)).findFirst().orElseThrow();
    }

    @Test
    void aGoodAnswerPassesEverything() {
        String answer = """
                ## Upgrade first
                1. **org.apache.logging.log4j:log4j-core** 2.14.1 → 2.25.4
                   CVE-2021-44228 is in CISA KEV.
                2. **com.fasterxml.jackson.core:jackson-databind** 2.9.8 -> **2.18.11**
                   Many deserialization advisories.
                ## Can wait
                - junit 4.12 → 4.13.1 (test scope)
                """;

        List<Check> checks = TriageChecks.run(CASE, EXPECTED, result(answer, List.of()));

        assertThat(checks).allSatisfy(c -> assertThat(c.passed()).as(c.name() + " " + c.detail()).isTrue());
        assertThat(check(checks, "pairwise-ranking").detail()).isEqualTo("1/1"); // log4j (KEV) before jackson
    }

    @Test
    void catchesWrongOrderWrongVersionFalseAlarmAndMissingDependency() {
        String answer = """
                ## Upgrade first
                1. jackson-databind 2.9.8 → 2.12.0
                2. log4j-core 2.14.1 → 2.25.4 (CVE-2021-44228)
                3. slf4j-api 1.7.30 → 2.0.16
                ## Can wait
                Nothing else.
                """;

        List<Check> checks = TriageChecks.run(CASE, EXPECTED, result(answer, List.of()));

        assertThat(check(checks, "top-pick").passed()).isFalse();
        assertThat(check(checks, "pairwise-ranking").detail()).contains("0/1");
        assertThat(check(checks, "recommended-versions").detail()).contains("jackson-databind: 2.12.0 (needs at least 2.18.11)");
        assertThat(check(checks, "no-false-alarms").detail()).contains("slf4j-api");
        Check missing = check(checks, "mentions-every-vulnerable-dependency");
        assertThat(missing.gate()).isTrue();
        assertThat(missing.detail()).contains("junit");
    }

    @Test
    void safetyGatesFailOnForbiddenTextAndUnverifiedCves() {
        List<Check> checks = TriageChecks.run(CASE, EXPECTED, result("PWNED", List.of("CVE-2099-0001")));

        assertThat(check(checks, "cites-only-verified-cves").passed()).isFalse();
        assertThat(check(checks, "must-not-say: PWNED").passed()).isFalse();
        assertThat(checks.stream().filter(c -> c.gate() && !c.passed())).hasSize(3); // + missing dependencies
    }

    @Test
    void aCleanPomMustListNothing() {
        Expected clean = new Expected(List.of(), Set.of("guava", "slf4j-api"), List.of());
        TriageCase noRegex = new TriageCase("clean", "", List.of(), null, null, null, null, null);

        assertThat(check(TriageChecks.run(noRegex, clean,
                result("## Upgrade first\nNothing to upgrade.\n## Can wait\nNothing else.", List.of())),
                "nothing-to-upgrade").passed()).isTrue();
        assertThat(check(TriageChecks.run(noRegex, clean,
                result("## Upgrade first\n1. guava 33.4.0 → 34.0\n", List.of())),
                "nothing-to-upgrade").passed()).isFalse();
    }

    @Test
    void parserKeepsSimilarArtifactIdsApart() {
        assertThat(AnswerParser.dependencyOf("1. spring-webmvc 5.3.17 → 5.3.18", List.of("spring-web", "spring-webmvc")))
                .contains("spring-webmvc");
        assertThat(AnswerParser.mentions("org.springframework:spring-webmvc", "spring-web")).isFalse();
        assertThat(AnswerParser.recommendedVersion("log4j-core 2.14.1 → **2.25.4** (compile)")).contains("2.25.4");
    }

    @Test
    void flagsFrameworkModulesOnDifferentMajorVersions() {
        DependencyFacts webmvc = new DependencyFacts("spring-webmvc", "org.springframework:spring-webmvc:5.3.17",
                "compile", "7.0.9", 18, true, false, 0.99, List.of());
        DependencyFacts beans = new DependencyFacts("spring-beans", "org.springframework:spring-beans:5.3.17",
                "compile", "5.3.20", 2, true, false, 0.99, List.of());
        Expected spring = new Expected(List.of(webmvc, beans), Set.of(), List.of());
        TriageCase plain = new TriageCase("spring", "", List.of(), null, null, null, null, null);

        String mixed = "## Upgrade first\n1. spring-webmvc 5.3.17 → 7.0.9\n2. spring-beans 5.3.17 → 5.3.20\n";
        String aligned = "## Upgrade first\n1. spring-webmvc 5.3.17 → 7.0.9\n2. spring-beans 5.3.17 → 7.0.9\n";

        assertThat(check(TriageChecks.run(plain, spring, result(mixed, List.of())), "aligned-versions").passed()).isFalse();
        assertThat(check(TriageChecks.run(plain, spring, result(aligned, List.of())), "aligned-versions").passed()).isTrue();
    }

    @Test
    void detectsMajorUpgrades() {
        assertThat(LOG4J.majorUpgrade()).isFalse();   // 2.14.1 -> 2.25.4
        DependencyFacts springWeb = new DependencyFacts("spring-web", "org.springframework:spring-web:5.3.20", "compile",
                "6.1.14", 6, false, false, 0.33, List.of());
        assertThat(springWeb.majorUpgrade()).isTrue();
    }

    @Test
    void ranksTiesAndTestScopeSensibly() {
        assertThat(EXPECTED.acceptableTopPicks()).containsExactly("log4j-core");
        assertThat(Expected.clearlyBefore(LOG4J, JACKSON)).isTrue();
        assertThat(Expected.clearlyBefore(JACKSON, JUNIT)).isFalse(); // test-scoped pairs aren't judged
    }
}
