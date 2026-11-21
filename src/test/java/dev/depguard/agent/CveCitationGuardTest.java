package dev.depguard.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class CveCitationGuardTest {

    @Test
    void passesWhenEveryCitedCveCameFromATool() {
        CveCitationGuard.Result result = CveCitationGuard.check(
                "Fix CVE-2021-44228 first, then CVE-2022-42889. CVE-2021-44228 is in KEV.",
                Set.of("CVE-2021-44228", "CVE-2022-42889", "CVE-2020-15250"));

        assertThat(result.cited()).containsExactly("CVE-2021-44228", "CVE-2022-42889");
        assertThat(result.passed()).isTrue();
    }

    @Test
    void flagsCvesNoToolReturned() {
        CveCitationGuard.Result result = CveCitationGuard.check(
                "Also watch CVE-2099-12345.", Set.of("CVE-2021-44228"));

        assertThat(result.unverified()).containsExactly("CVE-2099-12345");
        assertThat(result.passed()).isFalse();
    }

    @Test
    void handlesEmptyAnswer() {
        assertThat(CveCitationGuard.check(null, Set.of()).cited()).isEmpty();
    }
}
