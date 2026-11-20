package dev.depguard.dependency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PomParserTest {

    private final PomParser parser = new PomParser();

    @Test
    void readsDependenciesAndResolvesPropertyVersions() {
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <version>9.9.9</version>
                  <properties><spring.version>5.3.20</spring.version></properties>
                  <dependencies>
                    <dependency><groupId>org.springframework</groupId><artifactId>spring-web</artifactId><version>${spring.version}</version></dependency>
                    <dependency><groupId>com.example</groupId><artifactId>sibling</artifactId><version>${project.version}</version></dependency>
                    <dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.12</version><scope>test</scope></dependency>
                  </dependencies>
                </project>
                """;

        ParsedPom result = parser.parse(pom);

        assertThat(result.dependencies()).containsExactly(
                new Dependency("org.springframework", "spring-web", "5.3.20", "compile"),
                new Dependency("com.example", "sibling", "9.9.9", "compile"),
                new Dependency("junit", "junit", "4.12", "test"));
        assertThat(result.skipped()).isEmpty();
    }

    @Test
    void skipsDependenciesWhoseVersionWeCannotKnow() {
        String pom = """
                <project>
                  <dependencies>
                    <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId></dependency>
                    <dependency><groupId>a</groupId><artifactId>b</artifactId><version>${undefined}</version></dependency>
                  </dependencies>
                </project>
                """;

        ParsedPom result = parser.parse(pom);

        assertThat(result.dependencies()).isEmpty();
        assertThat(result.skipped()).containsExactly(
                "org.slf4j:slf4j-api (version managed by a parent pom or BOM)",
                "a:b (could not resolve version ${undefined})");
    }

    @Test
    void ignoresDependencyManagementSection() {
        String pom = """
                <project>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>x</groupId><artifactId>managed</artifactId><version>1.0</version></dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;

        assertThat(parser.parse(pom).dependencies()).isEmpty();
    }

    @Test
    void rejectsXxeAttack() {
        String malicious = """
                <?xml version="1.0"?>
                <!DOCTYPE project [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <project><version>&secret;</version></project>
                """;

        assertThatThrownBy(() -> parser.parse(malicious))
                .isInstanceOf(PomParseException.class)
                .hasMessageContaining("DOCTYPE");
    }

    @Test
    void rejectsTextThatIsNotAPom() {
        assertThatThrownBy(() -> parser.parse("not xml at all")).isInstanceOf(PomParseException.class);
        assertThatThrownBy(() -> parser.parse("<html/>"))
                .isInstanceOf(PomParseException.class)
                .hasMessage("Root element must be <project>");
    }
}
