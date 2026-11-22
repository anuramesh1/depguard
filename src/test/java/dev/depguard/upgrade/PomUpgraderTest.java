package dev.depguard.upgrade;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PomUpgraderTest {

    private static final String POM = """
            <project>
              <!-- keep this comment -->
              <dependencies>
                <dependency>
                  <groupId>org.apache.logging.log4j</groupId>
                  <artifactId>log4j-core</artifactId>
                  <version>2.14.1</version>
                </dependency>
                <dependency>
                  <groupId>com.fasterxml.jackson.core</groupId>
                  <artifactId>jackson-databind</artifactId>
                  <version>${jackson.version}</version>
                </dependency>
              </dependencies>
            </project>
            """;

    @Test
    void changesOnlyTheVersionTextAndKeepsEverythingElse() {
        var result = PomUpgrader.apply(POM, List.of(
                new PomUpgrader.Change("org.apache.logging.log4j", "log4j-core", "2.14.1", "2.25.4")));

        assertThat(result.pom()).isEqualTo(POM.replace("<version>2.14.1</version>", "<version>2.25.4</version>"));
        assertThat(result.pom()).contains("<!-- keep this comment -->");
        assertThat(result.applied()).hasSize(1);
        assertThat(result.skipped()).isEmpty();
    }

    @Test
    void propertyManagedOrMissingVersionsAreReportedNotGuessed() {
        var result = PomUpgrader.apply(POM, List.of(
                new PomUpgrader.Change("com.fasterxml.jackson.core", "jackson-databind", "2.9.8", "2.18.11"),
                new PomUpgrader.Change("org.example", "absent", "1.0", "2.0")));

        assertThat(result.pom()).isEqualTo(POM);
        assertThat(result.applied()).isEmpty();
        assertThat(result.skipped()).anySatisfy(s -> assertThat(s).contains("${jackson.version}", "by hand"))
                .anySatisfy(s -> assertThat(s).contains("org.example:absent not found"));
    }
}
